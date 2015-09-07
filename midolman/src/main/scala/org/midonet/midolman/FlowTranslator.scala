/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman

import java.lang.{Integer => JInteger}
import java.util.{ArrayList, UUID}
import java.util.concurrent.TimeUnit

import org.midonet.midolman.simulation.Simulator.{VxlanEncap, VxlanDecap, ToPortAction}

import scala.concurrent.ExecutionContextExecutor

import akka.actor.ActorSystem
import akka.util.Timeout

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.{VxLanPort, PacketContext}
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.midolman.simulation.Port
import org.midonet.odp.flows.FlowActions.{output, setKey}
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.sdn.flows.{FlowTagger, VirtualActions}

object FlowTranslator {
    val NotADpPort: JInteger = -1
}

trait FlowTranslator {
    import FlowTranslator._
    import VirtualActions._

    protected val dpState: DatapathState
    protected val hostId: UUID
    protected val vxlanRecircMap: VxlanRecircMap
    protected val config: MidolmanConfig

    implicit protected val requestReplyTimeout = new Timeout(5, TimeUnit.SECONDS)
    implicit protected def system: ActorSystem
    implicit protected def executor: ExecutionContextExecutor = system.dispatcher

    /**
     * Translates a Seq of FlowActions expressed in virtual references into a
     * Seq of FlowActions expressed in physical references.
     */
    def translateActions(context: PacketContext): Unit = {
        if (context.innerLayer == null)
            translateActions__(context)
        else {
            context.log.debug(s"Translate for a simulation that added an " +
                              s"encapsulation header with key ${context.innerLayer.vni}.")
            // An encapsulation header was added in-line and simulation
            // continued. This doesn't require recirculation, so we just
            // use outer packet/match's:
            // 1) exterior egress ports, to know where to propagate flow state
            // 2) final match fields, to know the tunnel's final destination.
            val outerVirtualFlowActions = context.virtualFlowActions
            // TODO: use the ToPortActions from the outer packet
            val outerMatch = context.wcmatch
            context.log.debug(s"The outer virtual actions are ${outerVirtualFlowActions} " +
                              s"and the outer match is ${outerMatch}")
            val vni = context.innerLayer.vni
            context.decap() // The inner layer replaces the outer one.
            context.calculateActionsFromMatchDiff()
            translateActions__(context)
            // We currently handle the inner packet by tunneling it directly to
            // the destination of the outer packet. This is only possible if the
            // local host has connectivity, signaled by the router L2 port's
            // 'offRampVxlan' field.
            // In the future, we might provide an alternative: tunnel the inner
            // packet to the peer implied by the outer packet's ToPortActions,
            // and use a Flow State message to communicate the outer packet's
            // header. The peer will then forward it to its final destination.
            // Obviously, this only works if the outer packet needs to egress a
            // remote virtual port.
            context.addFlowAndPacketAction(setKey(FlowKeys.tunnel(
                vni,
                // Is this ok or do we need to use the host's local IP?
                //outerMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr].toInt,
                IPv4Addr("192.168.192.118").toInt,
                outerMatch.getNetworkDstIP.asInstanceOf[IPv4Addr].toInt,
                0,
                outerMatch.getNetworkTTL)))
            // We emit this from the VTEP tunnel port because it uses the
            // standard VXLAN UDP port.
            context.addFlowAndPacketAction(dpState.vtepTunnellingOutputAction)
            context.log.debug("With the outer header accounted for, the flow " +
                              s"actions are ${context.flowActions}")
        }
    }

    private def prepareDecapRecirc(context: PacketContext, routerId: UUID): Unit = {
        // The diff doesn't matter because the whole outer layer will be
        // removed. Instead, we modify outer fields so that the decap'ed packet
        // will be received by the vxlan recirc tunnel port. Choose:
        // - the hostRecircPort (non-Vxlan) as the output port.
        // - the recircMnAddress as the source IP
        // - the host's Recirc Address as the destination IP
        // - the TOS and TTL such that together (16 bits) they can be used
        //   to uniquely identify (only within this process) the router where
        //   the inner packet's simulation should begin (by egressing the
        //   appropriate L2 port). It would have been nice to use the source
        //   UDP port for this, but matching on the tunnel source UDP port is
        //   not possible until the Linux 4.0 kernel.
        // - the source udp port - anything.
        // - config.datapath.vxlanRecirculateUdpPort as the dst udp port
        //
        // WARNING: on decap, we encode the Router Id NOT the egress L2port Id
        // because the outer flow may contain many inner flows with different
        // VNIs, that therefore need to egress different L2 ports.
        val routerInt = vxlanRecircMap.routerToInt(routerId)
        val tosTtl = VxlanRecircMap.intToBytePair(routerInt)
        context.log.debug("Packet will be decap'ed and recirculated to " +
                          s"router ${routerId} encoded as integer $routerInt " +
                          s"and tosTtl bytes $tosTtl")
        context.addFlowAndPacketAction(setKey(
            FlowKeys.ethernet(MAC.fromString(config.recircMnMac).getAddress,
                              MAC.fromString(config.recircHostMac).getAddress)))
        context.addFlowAndPacketAction(setKey(
            FlowKeys.ipv4(IPv4Addr(config.recircMnAddr),
                          IPv4Subnet.fromCidr(config.recircHostCidr).getAddress,
                          context.wcmatch.getNetworkProto,
                          tosTtl._1,
                          tosTtl._2,
                          context.wcmatch.getIpFragmentType)))
        context.addFlowAndPacketAction(setKey(
            FlowKeys.udp(12345, // can be anything
                         config.datapath.vxlanRecirculateUdpPort)))
        context.addFlowAndPacketAction(
            FlowActions.output(dpState.hostRecircPort))
    }

    private def prepareEncapRecirc(context: PacketContext, vni: Int,
                           routerL2portId: UUID, remoteVtep: IPv4Addr): Unit = {
        // Emit the packet from the vxlan recirc tunnel port so that it gets
        // encapsulated. But then we need it to come back into the datapath for
        // further processing.
        // We can only match on the outer L2-L4 headers, not the inner ones.
        // Therefore, to avoid collisions between inner flows with different
        // VNIs and/or destination MACs, we need to encode BOTH the portId AND
        // either the dst MAC or dst VTEP in the outer headers. We choose to
        // encode the dst VTEP because there are few distinct values. The
        // routerId can be obtained from the portId.
        val i = vxlanRecircMap.portVtepToInt(routerL2portId, remoteVtep)
        val tosTtl = VxlanRecircMap.intToBytePair(i)
        context.addFlowAndPacketAction(
            setKey(FlowKeys.tunnel(
                vni,
                IPv4Subnet.fromCidr(config.recircHostCidr).getIntAddress,
                IPv4Addr(config.recircMnAddr).toInt,
                tosTtl._1,
                tosTtl._2)))
        context.addFlowAndPacketAction(
            dpState.vxlanRecircOutputAction)
        context.log.debug("Packet will be encap'ed and recirculated to " +
                          s"routerL2portId $routerL2portId for remote Vtep " +
                          s"$remoteVtep encoded as integer $i and tosTtl " +
                          s"bytes $tosTtl")
    }

    def translateActions__(context: PacketContext): Unit = {
        context.outPorts.clear()

        val virtualActions = context.virtualFlowActions
        val actionsCount = virtualActions.size()
        var i = 0
        while (i < actionsCount) {
            virtualActions.get(i) match {
                case ToPortAction(port) =>
                    expandPortAction(port, context)
                case a: FlowActionSetKey =>
                    a.getFlowKey match {
                        case k: FlowKeyICMPError =>
                            mangleIcmp(context.ethernet, k.icmp_data)
                        case k: FlowKeyICMPEcho =>
                        case _ =>
                            context.addFlowAndPacketAction(a)
                    }
                case a: FlowActionUserspace =>
                    context.flowActions.add(a)
                case VxlanDecap(routerId) =>
                    prepareDecapRecirc(context, routerId)
                case VxlanEncap(vni, routerL2portId, remoteVtep) =>
                    prepareEncapRecirc(context, vni, routerL2portId, remoteVtep)
                case a =>
                    context.addFlowAndPacketAction(a)
            }
            i += 1
        }

        context.log.debug(s"Translated actions to: ${context.flowActions}")
    }

    // This is very limited but we don't really need more
    // This method takes a Ethernet packet and modifies it if it carries an
    // icmp payload
    private def mangleIcmp(eth: Ethernet, data: Array[Byte]) {
        eth.getPayload match {
            case ipv4: IPv4 =>
                ipv4.getPayload match {
                    case icmp: ICMP =>
                        icmp.setData(data)
                        icmp.clearChecksum()
                    case _ =>
                }
            case _ =>
        }
    }

    /** Update the list of action and list of tags with the output actions
     *  for the given list of local datapath port numbers. */
    private def outputActionsForLocalPort(portNo: JInteger,
                                          context: PacketContext): Unit = {
        context.log.debug(s"Emitting towards local dp port $portNo")
        if (portNo != NotADpPort) {
            context.addFlowAndPacketAction(output(portNo))
            context.addFlowTag(FlowTagger tagForDpPort portNo)
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  actions for the given list of remote host and tunnel key. */
    def outputActionsToPeer(key: Long, peer: UUID,
                            context: PacketContext): Unit = {
        context.log.debug(s"Emitting towards remote host $peer with tunnel key $key")
        val routeInfo = dpState.peerTunnelInfo(peer)
        if (routeInfo.isEmpty) {
            context.log.warn("Unable to tunnel to peer {}, is the peer "+
                "in the same tunnel zone as the current node?", peer)
        } else {
            val src = routeInfo.get.srcIp
            val dst = routeInfo.get.dstIp
            context.addFlowTag(FlowTagger.tagForTunnelRoute(src, dst))
            // Each FlowActionSetKey must be followed by a corresponding
            // FlowActionOutput.
            if (context.tracingEnabled) {
                context.flowActions.add(
                    setKey(FlowKeys.tunnel(key, src, dst, 0)))
                context.packetActions.add(
                    setKey(FlowKeys.tunnel(context.setTraceTunnelBit(key),
                                           src, dst, 0)))
            } else {
                context.addFlowAndPacketAction(
                    setKey(FlowKeys.tunnel(key, src, dst, 0)))
            }
            context.addFlowAndPacketAction(routeInfo.get.output)
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  action for the given vtep tunnel addr and vni key. The tzId is the
     *  id of the tunnel zone specified by this VTEP's config which allows
     *  us to determine which of the host's IP we should use */
    private def outputActionsToVtep(vni: Int, vtepIp: IPv4Addr, tzId: UUID,
                                    context: PacketContext): Unit = {
        context.log.debug(s"Emitting towards vtep at $vtepIp with vni $vni")

        val host = VirtualToPhysicalMapper.tryAsk(new HostRequest(hostId))
        val tzMembership = host.tunnelZones.get(tzId)

        if (tzMembership eq None) {
            context.log.warn(s"Can't output to VTEP with tunnel IP: $vtepIp, host not in "
                             + s"VTEP's tunnel zone: $tzId")
            return
        }

        val localIp = tzMembership.get.asInstanceOf[IPv4Addr].toInt
        val vtepIntIp = vtepIp.toInt
        context.addFlowTag(FlowTagger.tagForTunnelRoute(localIp, vtepIntIp))
        context.addFlowAndPacketAction(setKey(FlowKeys.tunnel(vni.toLong, localIp, vtepIntIp, 0)))
        context.addFlowAndPacketAction(dpState.vtepTunnellingOutputAction)
    }

    private def expandPortAction(port: UUID, context: PacketContext): Unit =
        dpState.getDpPortNumberForVport(port) match {
            case null => // Translate to a remote port or a vtep peer.
                tryAsk[Port](port) match {
                    case p: VxLanPort => // Always exterior
                        outputActionsToVtep(p.vtepVni, p.vtepTunnelIp,
                                            p.vtepTunnelZoneId, context)
                    case p: Port if p.isExterior =>
                        context.outPorts.add(port)
                        outputActionsToPeer(p.tunnelKey, p.hostId, context)
                    case _ =>
                        context.log.warn("Port {} was not exterior", port)
                }
            case portNum =>
                context.outPorts.add(port)
                // If the DPC has a local DP port for this UUID, translate
                outputActionsForLocalPort(portNum, context)
        }
}
