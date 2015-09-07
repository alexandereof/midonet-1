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

package org.midonet.cluster.services.rest_api.resources

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, UpdateOp}
import org.midonet.cluster.models.RouterVtepTranslation._
import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowGet, AllowList, _}
import org.midonet.cluster.rest_api.models.{RouterVtepBinding, UriResource}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1)
@Path("router_vtep_bindings")
@RequestScoped
@AllowGet(Array(APPLICATION_ROUTER_VTEP_BINDING_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_ROUTER_VTEP_BINDING_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_ROUTER_VTEP_BINDING_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_ROUTER_VTEP_BINDING_JSON,
                   APPLICATION_JSON))
@AllowDelete
class RouterVtepBindingResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[RouterVtepBinding](resContext) {

    protected override def multiResource(ops: Seq[Multi],
                                         r: Response = OkResponse): Response = {
        (ops.last match {
            case Create(t) =>
                val msg = toProto(t)
                log.debug("CREATE: {}\n{}", msg.getClass, msg)
                updateBindings(backend.store, CreateOp(msg))
            case Update(t) =>
                val msg = toProto(t)
                log.debug("UPDATE: {}\n{}", msg.getClass, msg)
                updateBindings(backend.store, UpdateOp(msg))
            case Delete(clazz, id) =>
                log.debug("DELETE: {}:{}", UriResource.getZoomClass(clazz),
                          id.asInstanceOf[AnyRef])
                updateBindings(backend.store,
                                 DeleteOp(UriResource.getZoomClass(clazz), id))
        }) match {
            case true => r
            case false =>
                Response.status(Response.Status.PRECONDITION_FAILED).build
        }
    }
}
