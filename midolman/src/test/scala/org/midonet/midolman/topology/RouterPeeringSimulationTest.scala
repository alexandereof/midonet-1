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

package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import com.typesafe.config.{Config, ConfigValueFactory}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.{IPAddress => PIPAddr, IPSubnet => PIPSubnet, IPVersion => PIPVersion}
import org.midonet.cluster.models.RouterVtepTranslation
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.{Port => TPort, Router => TRouter, MacIp, RouterVtepBinding, RouterVtepRoute}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.{Port, Router}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.concurrent.FutureOps
import org.midonet.util.reactivex._


@RunWith(classOf[JUnitRunner])
class RouterPeeringSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var stateStore: StateStorage = _
    private final val Timeout = 5 seconds

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor()))

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
    }

    protected override def fillConfig(config: Config) = {
        super.fillConfig(config).withValue("zookeeper.use_new_stack",
                                           ConfigValueFactory.fromAnyRef(true))
    }

    final implicit class MyFutureOps[T](val future: Future[T]) {

        def getOrThrow: T = Await.result(future, Timeout)
    }

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    def tryGet(thunk: () => Unit): Unit = {
        try {
            thunk()
        } catch {
            case e: NotYetException => e.waitFor.await(3 seconds)
        }
    }

    def packet(srcMac: String, srcIp: IPv4Addr,
               dstMac: String, dstIp: IPv4Addr): Ethernet = {
        {
            eth addr srcMac -> dstMac
        } << {
            ip4 addr srcIp --> dstIp
        } << {
            udp ports 9999 ---> 10999
        } <<
        payload(UUID.randomUUID().toString)
    }

    /**
     * Convert a java.util.UUID to a Protocol Buffers message.
     */
    implicit def toProto(ip: IPv4Addr): PIPAddr = {
        if (ip == null) {
            null
        }
        else {
            PIPAddr.newBuilder
                .setVersion(PIPVersion.V4)
                .setAddress(ip.toString)
                .build()
        }
    }

    /**
     * Convert a java.util.UUID to a Protocol Buffers message.
     */
    implicit def toProto(subnet: IPv4Subnet): PIPSubnet = {
        if (subnet == null) {
            null
        }
        else {
            PIPSubnet.newBuilder
                .setVersion(PIPVersion.V4)
                .setPrefixLength(subnet.getPrefixLen)
                .setAddress(subnet.getAddress.toString)
                .build()
        }
    }

    feature("Test overlay vtep") {

        scenario("Test two routers peered via 2 vteps") {
            // In this test we want to check that:
            // - a VM on subnet1 of Router1 can ping a VM on subnet2 of Router2
            // - when Router1 is connected to Vtep1
            // - and Router2 is connected to Vtep2
            // - and both routers are on the same VNI
            // - and the two vteps have connectivity
            // THIS ASSUMES THAT offRampVxlan IS ENABLED
            val host = createHost()
            store.create(host)

            // 1) Create 2 vteps and link them.
            val vtep1 = createRouter(name = Some("vtep1"),
                                       adminStateUp = true)
            val vtep2 = createRouter(name = Some("vtep2"),
                                       adminStateUp = true)
            store.create(vtep1)
            store.create(vtep2)
            // The vteps each have one L3 port. The two ports are interior and
            // are peered to each other.
            val vtep1Ip = IPv4Addr("10.0.222.1")
            val vtep2Ip = IPv4Addr("10.0.222.2")
            val vtep1Port = createRouterPort(
                routerId = Some(vtep1.getId),
                adminStateUp = true,
                portAddress = vtep1Ip,
                portSubnet = new IPv4Subnet(vtep1Ip, 30))
            val vtep2Port = createRouterPort(
                routerId = Some(vtep2.getId),
                peerId = Some(vtep1Port.getId),
                adminStateUp = true,
                portAddress = vtep2Ip,
                portSubnet = new IPv4Subnet(vtep2Ip, 30))
            store.create(vtep1Port)
            store.create(vtep2Port)
            store.update(vtep1Port.toBuilder.setPeerId(vtep2Port.getId).build)
            // Create routes to the vtep link
            store.create(createRoute(routerId = Some(vtep1.getId),
                                     srcNetwork = IPv4Subnet
                                         .fromCidr("0.0.0.0/0"),
                                     dstNetwork = new IPv4Subnet(vtep1Ip, 30),
                                     nextHop = NextHop.PORT,
                                     nextHopPortId = Some(vtep1Port.getId)))
            store.create(createRoute(routerId = Some(vtep2.getId),
                                     srcNetwork = IPv4Subnet
                                         .fromCidr("0.0.0.0/0"),
                                     dstNetwork = new IPv4Subnet(vtep2Ip, 30),
                                     nextHop = NextHop.PORT,
                                     nextHopPortId = Some(vtep2Port.getId)))
            // L2 ports on the vteps will be created and populated by the
            // translation of RouterVtepBinding and RouterVtepRoute

            // 2) Create two routers, each with 1 subnet port (different /24's)
            // Use exterior ports so that we can start simulations there.
            val router1 = createRouter(name = Some("router1"),
                                       adminStateUp = true)
            val router2 = createRouter(name = Some("router2"),
                                       adminStateUp = true)
            store.create(router1)
            store.create(router2)
            val router1PortIp = IPv4Addr("10.0.0.1")
            val router2PortIp = IPv4Addr("10.0.1.1")
            val router1PortMac = "02:bb:aa:aa:dd:01"
            val router2PortMac = "02:bb:aa:aa:dd:02"
            val router1Port = createRouterPort(
                hostId = Some(host.getId),
                interfaceName = Some("router1port"),
                routerId = Some(router1.getId),
                adminStateUp = true,
                portMac = MAC.fromString(router1PortMac),
                portAddress = router1PortIp,
                portSubnet = new IPv4Subnet(router1PortIp, 24))
            val router2Port = createRouterPort(
                hostId = Some(host.getId),
                interfaceName = Some("router2port"),
                routerId = Some(router2.getId),
                adminStateUp = true,
                portMac = MAC.fromString(router2PortMac),
                portAddress = router2PortIp,
                portSubnet = new IPv4Subnet(router2PortIp, 24))
            store.create(router1Port)
            store.create(router2Port)
            // Add routes to the subnets
            store.create(createRoute(routerId = Some(router1.getId),
                                     srcNetwork = IPv4Subnet
                                         .fromCidr("0.0.0.0/0"),
                                     dstNetwork = new IPv4Subnet(router1PortIp, 24),
                                     nextHop = NextHop.PORT,
                                     nextHopPortId = Some(router1Port.getId)))
            store.create(createRoute(routerId = Some(router2.getId),
                                     srcNetwork = IPv4Subnet
                                         .fromCidr("0.0.0.0/0"),
                                     dstNetwork = new IPv4Subnet(router2PortIp, 24),
                                     nextHop = NextHop.PORT,
                                     nextHopPortId = Some(router2Port.getId)))
            // Seed the ARP cache for exterior router ports to avoid ARP packets
            val vm1Ip = IPv4Addr("10.0.0.2")
            val vm2Ip = IPv4Addr("10.0.1.2")
            val vm1Mac = "02:aa:bb:cc:dd:01"
            val vm2Mac = "02:aa:bb:cc:dd:02"
            store.update(
                store.get(classOf[TRouter], router1.getId)
                    .getOrThrow
                    .toBuilder.addMacIpPairs(
                        MacIp.newBuilder.setMac(vm1Mac).setIp(vm1Ip).build)
                    .build)
            store.update(
                store.get(classOf[TRouter], router2.getId)
                    .getOrThrow
                    .toBuilder.addMacIpPairs(
                        MacIp.newBuilder.setMac(vm2Mac).setIp(vm2Ip).build)
                    .build)

            // 3) Now we have the Tenant Routers and the Admin Vteps
            // Create RouterVtepBindings and RouterVtepRoutes
            val router1PeeringIp = new IPv4Addr("10.0.100.1")
            val router1PeeringMac = "02:11:22:33:44:01"
            val router2PeeringIp = new IPv4Addr("10.0.100.2")
            val router2PeeringMac = "02:11:22:33:44:02"

            var binding1 = RouterVtepBinding.newBuilder
                .setRouterId(router1.getId)
                .setVtepId(vtep1.getId)
                .setVtepTunnelAddress(vtep1Ip)
                .setVni(12345)
                .setRouterPortAddress(router1PeeringIp)
                .setRouterPortSubnet(
                    toProto(new IPv4Subnet(router1PeeringIp, 30)))
                .setRouterPortMac(router1PeeringMac)
                .setId(UUIDUtil.toProto(UUID.randomUUID))
                .build
            RouterVtepTranslation
                .updateBindings(store, CreateOp(binding1)) should be(true)
            val binding1Route = RouterVtepRoute.newBuilder
                .setId(UUIDUtil.toProto(UUID.randomUUID))
                .setBindingId(binding1.getId)
                .setCidr(new IPv4Subnet(router2PortIp, 24))
                .setGwIp(router2PeeringIp)
                .setGwMac(router2PeeringMac)
                .setRemoteVtepIp(vtep2Ip)
                .build
            RouterVtepTranslation
                .updateRoutes(store, CreateOp(binding1Route)) should be(true)
            var binding2 = RouterVtepBinding.newBuilder
                .setRouterId(router2.getId)
                .setVtepId(vtep2.getId)
                .setVtepTunnelAddress(vtep2Ip)
                .setVni(12345)
                .setRouterPortAddress(router2PeeringIp)
                .setRouterPortSubnet(new IPv4Subnet(router2PeeringIp, 30))
                .setRouterPortMac(router2PeeringMac)
                .setId(UUIDUtil.toProto(UUID.randomUUID))
                .build
            RouterVtepTranslation
                .updateBindings(store, CreateOp(binding2)) should be(true)
            val binding2Route = RouterVtepRoute.newBuilder
                .setId(UUIDUtil.toProto(UUID.randomUUID))
                .setBindingId(binding2.getId)
                .setCidr(new IPv4Subnet(router1PortIp, 24))
                .setGwIp(router1PeeringIp)
                .setGwMac(router1PeeringMac)
                .setRemoteVtepIp(vtep1Ip)
                .build
            RouterVtepTranslation
                .updateRoutes(store, CreateOp(binding2Route)) should be(true)

            binding1 = store.get(
                classOf[RouterVtepBinding], binding1.getId).getOrThrow
            binding2 = store.get(
                classOf[RouterVtepBinding], binding2.getId).getOrThrow
            for (binding <- List(binding1, binding2)) {
                binding1.hasRouterPortId should be(true)
                binding1.hasVtepPortId should be(true)
            }


            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(vtep1Port, vtep2Port, router1Port, router2Port)) {
                tryGet(() => VirtualTopology.tryGet[Port](port.getId))
            }
            for (router <- List(vtep1, vtep2, router1, router2)) {
                tryGet(() => VirtualTopology.tryGet[Router](router.getId))
            }

            // Set the exterior ports active
            for (port <- List(router1Port, router2Port)) {
                stateStore.addValue(classOf[TPort], port.getId, HostsKey,
                                    host.toString).await(3 seconds)
            }

            // Now try sending a packet from VM1 to VM2
            When("VM1 sends a packet to VM2")
            var packetContext = packetContextFor(
                packet(vm1Mac, vm1Ip, router1PortMac, vm2Ip), router1Port.getId)
            var result = simulate(packetContext)

            Then("It is correctly emitted from router2Port")
            result should be(toPort(router2Port.getId)
                                 (FlowTagger.tagForPortRx(router1Port.getId),
                                  FlowTagger.tagForRouter(router1.getId),
                                  FlowTagger.tagForPortTx(binding1.getRouterPortId),
                                  FlowTagger.tagForPortRx(binding1.getVtepPortId),
                                  FlowTagger.tagForRouter(vtep1.getId),
                                  FlowTagger.tagForPortTx(vtep1Port.getId),
                                  FlowTagger.tagForPortRx(vtep2Port.getId),
                                  FlowTagger.tagForRouter(vtep2.getId),
                                  FlowTagger.tagForPortTx(binding2.getVtepPortId),
                                  FlowTagger.tagForPortRx(binding2.getRouterPortId),
                                  FlowTagger.tagForRouter(router2.getId),
                                  FlowTagger.tagForPortTx(router2Port.getId)))

            // Now try sending a packet from VM2 to VM1
            When("VM2 sends a packet to VM1")
            packetContext = packetContextFor(
                packet(vm2Mac, vm2Ip, router2PortMac, vm1Ip), router2Port.getId)
            result = simulate(packetContext)

            Then("It is correctly emitted from router1Port")
            result should be(toPort(router1Port.getId)
                                 (FlowTagger.tagForPortRx(router2Port.getId),
                                  FlowTagger.tagForRouter(router2.getId),
                                  FlowTagger.tagForPortTx(binding2.getRouterPortId),
                                  FlowTagger.tagForPortRx(binding2.getVtepPortId),
                                  FlowTagger.tagForRouter(vtep2.getId),
                                  FlowTagger.tagForPortTx(vtep2Port.getId),
                                  FlowTagger.tagForPortRx(vtep1Port.getId),
                                  FlowTagger.tagForRouter(vtep1.getId),
                                  FlowTagger.tagForPortTx(binding1.getVtepPortId),
                                  FlowTagger.tagForPortRx(binding1.getRouterPortId),
                                  FlowTagger.tagForRouter(router1.getId),
                                  FlowTagger.tagForPortTx(router1Port.getId)))

            // Finally, delete the vtep bindings and vtep routes
            RouterVtepTranslation.updateRoutes(store, DeleteOp(
                classOf[RouterVtepRoute], binding1Route.getId)) should be(true)
            RouterVtepTranslation.updateRoutes(store, DeleteOp(
                classOf[RouterVtepRoute], binding2Route.getId)) should be(true)
            RouterVtepTranslation.updateBindings(store, DeleteOp(
                classOf[RouterVtepBinding], binding1.getId)) should be(true)
            RouterVtepTranslation.updateBindings(store, DeleteOp(
                classOf[RouterVtepBinding], binding2.getId)) should be(true)
        }
    }
}
