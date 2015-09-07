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

package org.midonet.cluster.models

import java.util.UUID

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.IPv4Subnet

object RouterVtepTranslation {

    val log = LoggerFactory.getLogger("RouterVtepTranslation")
    private final val Timeout = 5 seconds

    final class FutureOps[T](val future: Future[T]) extends AnyVal {

        def getOrThrow: T = Await.result(future, Timeout)
    }

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    def updateBindings(store: Storage, op: PersistenceOp): Boolean = {
        var ops = Seq.empty[PersistenceOp]
        op match {
            case CreateOp(i: RouterVtepBinding) =>
                // Fetch the router and the vtep
                val router = store.get(classOf[Router], i.getRouterId).getOrThrow
                val vtep = store.get(classOf[Router], i.getVtepId).getOrThrow
                log.debug(s"Creating a binding with vtep ${vtep.getName} ${fromProto(i.getVtepId)} and " +
                          s"router ${router.getName} ${fromProto(i.getRouterId)}")
                val vtepPortId = UUID.randomUUID()
                val routerPortId = UUID.randomUUID()
                val routerPort = Port.newBuilder
                    .setAdminStateUp(true)
                    .setRouterId(i.getRouterId)
                    .setId(routerPortId)
                    .setPortMac(i.getRouterPortMac)
                    .setPortAddress(i.getRouterPortAddress)
                    .setPortSubnet(i.getRouterPortSubnet)
                    .build
                // Build the router port
                ops = ops :+ CreateOp(routerPort)
                // Build the vtep port
                ops = ops :+ CreateOp(
                    Port.newBuilder
                        .setAdminStateUp(true)
                        .setRouterId(i.getVtepId)
                        .setId(vtepPortId)
                        .setPeerId(routerPortId)
                        .setVni(i.getVni)
                        .setOffRampVxlan(false)
                        .setLocalVtep(i.getVtepTunnelAddress)
                        .build)
                ops = ops :+ UpdateOp(
                    routerPort.toBuilder.setPeerId(vtepPortId).build)
                // Create the routes
                val route1 = UUID.randomUUID()
                val route2 = UUID.randomUUID()
                ops = ops :+ CreateOp(
                    Route.newBuilder
                        .setId(route1)
                        .setSrcSubnet(IPSubnetUtil.toProto(IPv4Subnet.fromCidr("0.0.0.0/0")))
                        .setDstSubnet(i.getRouterPortSubnet)
                        .setNextHop(NextHop.PORT)
                        .setNextHopPortId(routerPortId)
                        .setWeight(100)
                        .setRouterId(i.getRouterId)
                        .build)
                ops = ops :+ CreateOp(
                    Route.newBuilder
                        .setId(route2)
                        .setSrcSubnet(IPSubnetUtil.toProto(IPv4Subnet.fromCidr("0.0.0.0/0")))
                        .setDstSubnet(IPSubnetUtil.toProto(new IPv4Subnet(i.getRouterPortAddress.getAddress, 32)))
                        .setNextHop(NextHop.LOCAL)
                        .setWeight(100)
                        .setRouterId(i.getRouterId)
                        .build)
                // Create Binding last because it refers to the ports and routes
                ops = ops :+ CreateOp(
                    i.toBuilder
                        .setVtepPortId(vtepPortId)
                        .setRouterPortId(routerPortId)
                        .setRoute1(route1)
                        .setRoute2(route2)
                        .build)

            case UpdateOp(i: RouterVtepBinding, _) =>
                // TODO: implement me
                return false
            case DeleteOp(clazz, id, ignoreMissing) =>
                if (clazz != classOf[RouterVtepBinding])
                    return false
                try {
                    val binding = store.get(classOf[RouterVtepBinding], id).getOrThrow
                    val router = store.get(classOf[Router], binding.getRouterId).getOrThrow
                    val vtep = store.get(classOf[Router], binding.getVtepId).getOrThrow
                    if (binding.getVtepRouteCount > 0) {
                        log.warn(s"${binding.getVtepRouteCount} VtepRoutes must be removed " +
                                 s"before deleting binding ${id}} on vtep ${vtep.getName} " +
                                 s"and router ${router.getName}")
                        return false
                    }
                    log.debug(s"Deleting a binding with vtep ${vtep.getName} ${fromProto(binding.getVtepId)} and " +
                              s"router ${router.getName} ${fromProto(binding.getRouterId)}")
                    // Explicitly delete the routes. The LOCAL route does not
                    // mutually reference the router port (not auto-deleted).
                    ops = ops :+ DeleteOp(classOf[Route], binding.getRoute1, false)
                    ops = ops :+ DeleteOp(classOf[Route], binding.getRoute2, false)
                    ops = ops :+ DeleteOp(classOf[Port], binding.getRouterPortId, false)
                    ops = ops :+ DeleteOp(classOf[Port], binding.getVtepPortId, false)
                    // Add the delete operation for the binding itself.
                    ops = ops :+ op
                }
                catch {
                    case e: Exception =>
                        ignoreMissing match {
                            case true => return true
                            case false => return false
                        }
                }
            case _ =>
                return false
        }
        store.multi(ops)
        true
    }

    def updateRoutes(store: Storage, op: PersistenceOp): Boolean = {
        var ops = Seq.empty[PersistenceOp]
        op match {
            case CreateOp(i: RouterVtepRoute) =>
                // Fetch the binding, the router, the vtep, and the vtep port
                val binding = store.get(
                    classOf[RouterVtepBinding], i.getBindingId).getOrThrow
                val router =
                    store.get(classOf[Router], binding.getRouterId).getOrThrow
                val vtep =
                    store.get(classOf[Router], binding.getVtepId).getOrThrow
                val vtepPort =
                    store.get(classOf[Port], binding.getVtepPortId).getOrThrow
                // Add the route to the router
                val routeId = UUID.randomUUID
                ops = ops :+ CreateOp(
                    Route.newBuilder
                        .setId(routeId)
                        .setRouterId(binding.getRouterId)
                        .setWeight(100)
                        .setSrcSubnet(IPSubnetUtil.toProto("0.0.0.0/0"))
                        .setDstSubnet(i.getCidr)
                        .setNextHop(NextHop.PORT)
                        .setNextHopPortId(binding.getRouterPortId)
                        .setNextHopGateway(i.getGwIp)
                        .build)
                // Seed the router with the gateway's ARP info
                // Remove the old MAC-IP for that that MAC (if any exists)
                val routerBuilder = router.toBuilder
                var oldPairs = router.getMacIpPairsList.asScala
                routerBuilder.clearMacIpPairs()
                for (pair <- oldPairs) {
                    if (pair.getMac != i.getGwMac) {
                        routerBuilder.addMacIpPairs(pair)
                    }
                }
                routerBuilder.addMacIpPairs(
                    MacIp.newBuilder.setMac(i.getGwMac).setIp(i.getGwIp).build)
                ops = ops :+ UpdateOp(routerBuilder.build)

                // Map gw mac to remote VTEP ip in the local VTEP port
                // Remove the old remote VTEP for that that MAC (if any exists)
                val vtepPortBuilder = vtepPort.toBuilder
                oldPairs = vtepPort.getRemoteVtepsList.asScala
                vtepPortBuilder.clearRemoteVteps()
                for (pair <- oldPairs) {
                    if (pair.getMac != i.getGwMac) {
                        vtepPortBuilder.addRemoteVteps(pair)
                    }
                }
                vtepPortBuilder.addRemoteVteps(
                    MacIp.newBuilder
                        .setMac(i.getGwMac).setIp(i.getRemoteVtepIp).build)
                ops = ops :+ UpdateOp(vtepPortBuilder.build)
                ops = ops :+ CreateOp(i.toBuilder.setRoute(routeId).build)
            case UpdateOp(i: RouterVtepRoute, _) =>
                // Don't support this. Routes are easy to delete and recreate.
                return false
            case DeleteOp(clazz, id, ignoreMissing) =>
                if (clazz != classOf[RouterVtepRoute])
                    return false
                try {
                    val vtepRoute = store.get(classOf[RouterVtepRoute], id).getOrThrow
                    ops = ops :+ DeleteOp(classOf[Route], vtepRoute.getRoute, false)
                    // Add the delete operation for the vtepRoute itself
                    ops = ops :+ op
                    // TODO: delete the MacIp pairs in the Router and Vtep port
                }
                catch {
                    case e: Exception =>
                        ignoreMissing match {
                            case true => return true
                            case false => return false
                        }
                }
            case _ =>
                return false
        }
        store.multi(ops)
        true
    }
}
