/*********************************************************************************
 *
 * Catena-X - Product Passport Consumer Backend
 *
 * Copyright (c) 2022, 2023 BASF SE, BMW AG, Henkel AG & Co. KGaA
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the
 * License for the specific language govern in permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.tractusx.productpass.http.controllers;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.tractusx.productpass.config.DtrConfig;
import org.eclipse.tractusx.productpass.config.ProcessConfig;
import org.eclipse.tractusx.productpass.exceptions.ControllerException;
import org.eclipse.tractusx.productpass.exceptions.DataModelException;
import org.eclipse.tractusx.productpass.exceptions.ServiceException;
import org.eclipse.tractusx.productpass.managers.ProcessManager;
import org.eclipse.tractusx.productpass.models.catenax.Dtr;
import org.eclipse.tractusx.productpass.models.dtregistry.DigitalTwin3;
import org.eclipse.tractusx.productpass.models.dtregistry.EndPoint3;
import org.eclipse.tractusx.productpass.models.dtregistry.SubModel3;
import org.eclipse.tractusx.productpass.models.edc.DataPlaneEndpoint;
import org.eclipse.tractusx.productpass.models.edc.Jwt;
import org.eclipse.tractusx.productpass.models.http.Response;
import org.eclipse.tractusx.productpass.models.http.requests.Search;
import org.eclipse.tractusx.productpass.models.manager.History;
import org.eclipse.tractusx.productpass.models.manager.SearchStatus;
import org.eclipse.tractusx.productpass.models.manager.Status;
import org.eclipse.tractusx.productpass.models.passports.Passport;
import org.eclipse.tractusx.productpass.services.AasService;
import org.eclipse.tractusx.productpass.services.DataPlaneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import utils.*;

import java.util.Map;

/**
 * This class consists exclusively to define the HTTP methods of the Application's controller.
 **/
@RestController
@Tag(name = "Public Controller")
public class AppController {

    /** ATTRIBUTES **/
    @SuppressWarnings("Unused")
    private @Autowired HttpServletRequest httpRequest;
    private @Autowired HttpServletResponse httpResponse;
    @Autowired
    HttpUtil httpUtil;
    @Autowired
    EdcUtil edcUtil;
    @SuppressWarnings("Unused")
    @Autowired
    JsonUtil jsonUtil;
    @SuppressWarnings("Unused")
    @Autowired
    Environment env;
    @SuppressWarnings("Unused")
    @Autowired
    PassportUtil passportUtil;
    @Autowired
    AasService aasService;
    @Autowired
    DataPlaneService dataPlaneService;
    @Autowired
    ProcessManager processManager;
    @Autowired
    DtrConfig dtrConfig;
    @SuppressWarnings("Unused")
    private @Autowired ProcessConfig processConfig;

    /** METHODS **/
    @GetMapping("/")
    @Hidden                     // hides this endpoint from api documentation - swagger-ui
    public Response index() {
        httpUtil.redirect(httpResponse, "/passport");
        return httpUtil.getResponse("Redirect to UI");
    }

    /**
     * Checks the backend health status.
     * <p>
     *
     * @return  a {@code Response} HTTP response with the status.
     *
     */
    @GetMapping("/health")
    @Operation(summary = "Returns the backend health status", responses = {
            @ApiResponse(description = "Gets the application health", responseCode = "200", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = Response.class)))
    })
    public Response health() {
        Response response = httpUtil.getResponse(
                "RUNNING",
                200
        );
        response.data = DateTimeUtil.getDateTimeFormatted(null);
        return response;
    }

    /**
     * HTTP POST method to get the Digital Twin for the given processId and endpointId in the URL.
     * <p>
     * @param   processId
     *          the {@code String} id of the application's process.
     * @param   endpointId
     *          the {@code String} id of the endpoint.
     *
     * @return this {@code Response} HTTP response with the status.
     *
     */
    @RequestMapping(value = "/endpoint/{processId}/{endpointId}", method = RequestMethod.POST)
    @Operation(summary = "Receives the EDR for the EDC Consumer and queries for the dDTR")
    public Response getDigitalTwin(@RequestBody Object body, @PathVariable String processId, @PathVariable String endpointId) {
        try {
            DataPlaneEndpoint endpointData = null;
            try {
                endpointData = this.getEndpointData(body);
            } catch (Exception e) {
                return httpUtil.buildResponse(httpUtil.getBadRequest(e.getMessage()), httpResponse);
            }
            if (endpointData == null) {
                return httpUtil.buildResponse(httpUtil.getBadRequest("Failed to get data plane endpoint data"), httpResponse);
            }

            if (!processManager.checkProcess(processId)) {
                return httpUtil.buildResponse(httpUtil.getNotFound("Process not found!"), httpResponse);
            }

            Status status = processManager.getStatus(processId);
            if(status == null){
                return httpUtil.buildResponse(httpUtil.getNotFound("No status is created"), httpResponse);
            }

            SearchStatus searchStatus = processManager.getSearchStatus(processId);
            Search search = searchStatus.getSearch();
            if(search == null){
                return httpUtil.buildResponse(httpUtil.getNotFound("No search performed"), httpResponse);
            }
            Dtr dtr = searchStatus.getDtr(endpointId);
            if(dtr == null){
                return httpUtil.buildResponse(httpUtil.getNotFound("No dtr available for this endpointId"), httpResponse);
            }
            // Start Digital Twin Query
            AasService.DecentralDigitalTwinRegistryQueryById digitalTwinRegistry = aasService.new DecentralDigitalTwinRegistryQueryById(
                    search,
                    endpointData
            );
            Long dtRequestTime = DateTimeUtil.getTimestamp();
            Thread digitalTwinRegistryThread = ThreadUtil.runThread(digitalTwinRegistry);
            // Wait for digital twin query
            digitalTwinRegistryThread.join();
            DigitalTwin3 digitalTwin = null;
            SubModel3 subModel = null;
            String connectorId = null;
            String assetId = null;
            String connectorAddress = null;
            try {
                digitalTwin = digitalTwinRegistry.getDigitalTwin();
                subModel = digitalTwinRegistry.getSubModel();
                connectorId = subModel.getIdShort();
                EndPoint3 endpoint = subModel.getEndpoints().stream().filter(obj -> obj.getInterfaceName().equals(dtrConfig.getEndpointInterface())).findFirst().orElse(null);
                if (endpoint == null) {
                    throw new ControllerException(this.getClass().getName(), "No EDC endpoint found in DTR SubModel!");
                }
                Map<String, String> subProtocolBody = endpoint.getProtocolInformation().getParsedSubprotocolBody();
                connectorAddress = subProtocolBody.get(dtrConfig.getDspEndpointKey()); // Get DSP endpoint address
                assetId = subProtocolBody.get("id"); // Get Asset Id
            } catch (Exception e) {
                return httpUtil.buildResponse(httpUtil.getNotFound("No endpoint address found"), httpResponse);
            }
            if (connectorId.isEmpty() || connectorAddress.isEmpty()) {
                return httpUtil.buildResponse(httpUtil.getNotFound("ConnectorId and Connector Address may be empty"), httpResponse);
            }

            try {
                connectorAddress = CatenaXUtil.buildEndpoint(connectorAddress);
            } catch (Exception e) {
                return null;
            }
            if (connectorAddress.isEmpty() || assetId.isEmpty()) {
                LogUtil.printError("Failed to parse endpoint [" + connectorAddress + "] or the assetId is not found!");
            }
            processManager.setEndpoint(processId, connectorAddress);
            processManager.setBpn(processId, dtr.getBpn());
            processManager.saveDigitalTwin3(processId, digitalTwin, dtRequestTime);
            LogUtil.printDebug("[PROCESS " + processId + "] Digital Twin [" + digitalTwin.getIdentification() + "] and Submodel [" + subModel.getIdentification() + "] with EDC endpoint [" + connectorAddress + "] retrieved from DTR");
            processManager.setStatus(processId, "digital-twin-found", new History(
                    assetId,
                    "READY"
            ));

        } catch (Exception e) {
            LogUtil.printException(e, "This request is not allowed! It must contain the valid attributes from an EDC endpoint");
            return httpUtil.buildResponse(httpUtil.getForbiddenResponse(), httpResponse);
        }
        return httpUtil.buildResponse(httpUtil.getResponse("ok"), httpResponse);
    }

    /**
     * Gets the {@code DataPlaneEndpoint} data from the given body of the HTTP request.
     * <p>
     * @param   body
     *          the {@code Object} body from the HTTP request.
     *
     * @return the {@code DataPlaneEndpoint} object.
     *
     * @throws ControllerException
     *           if the unable to get the data plane endpoint.
     *
     */
    public DataPlaneEndpoint getEndpointData(Object body) throws ControllerException {
        DataPlaneEndpoint endpointData = edcUtil.parseDataPlaneEndpoint(body);
        if (endpointData == null) {
            throw new ControllerException(this.getClass().getName(), "The endpoint data request is empty!");
        }
        if (endpointData.getEndpoint().isEmpty()) {
            throw new ControllerException(this.getClass().getName(), "The data plane endpoint address is empty!");
        }
        if (endpointData.getAuthCode().isEmpty()) {
            throw new ControllerException(this.getClass().getName(), "The authorization code is empty!");
        }
        if (!endpointData.offerIdExists()) {
            Jwt token = httpUtil.parseToken(endpointData.getAuthCode());
            if (!token.getPayload().containsKey("cid") || token.getPayload().get("cid").equals("")) {
                throw new ControllerException(this.getClass().getName(), "The Offer Id is empty!");
            }
        } else {
            if (endpointData.getOfferId().isEmpty()) {
                throw new ControllerException(this.getClass().getName(), "The authorization code is empty!");
            }
        }

        return endpointData;
    }

    /**
     * HTTP POST method to get the Passport for the given processId.
     * <p>
     * @param   processId
     *          the {@code String} id of the application's process.
     *
     * @return this {@code Response} HTTP response with the status.
     *
     */
    @RequestMapping(value = "/endpoint/{processId}", method = RequestMethod.POST)
    @Operation(summary = "Receives the EDR from the EDC Consumer and get the passport json")
    public Response endpoint(@RequestBody Object body, @PathVariable String processId) {
        try {
            DataPlaneEndpoint endpointData = null;
            try {
                endpointData = this.getEndpointData(body);
            } catch (Exception e) {
                return httpUtil.buildResponse(httpUtil.getBadRequest(e.getMessage()), httpResponse);
            }
            if (endpointData == null) {
                return httpUtil.buildResponse(httpUtil.getBadRequest("Failed to get data plane endpoint data"), httpResponse);
            }

            if (!processManager.checkProcess(processId)) {
                return httpUtil.buildResponse(httpUtil.getNotFound("Process not found!"), httpResponse);
            }

            Passport passport = dataPlaneService.getPassport(endpointData);
            if (passport == null) {
                return httpUtil.buildResponse(httpUtil.getNotFound("Passport not found in data plane!"), httpResponse);
            }
            String passportPath = processManager.savePassport(processId, endpointData, passport);

            LogUtil.printMessage("[EDC] Passport Transfer Data [" + endpointData.getId() + "] Saved Successfully in [" + passportPath + "]!");
        } catch (Exception e) {
            LogUtil.printException(e, "This request is not allowed! It must contain the valid attributes from an EDC endpoint");
            return httpUtil.buildResponse(httpUtil.getForbiddenResponse(), httpResponse);
        }
        return httpUtil.buildResponse(httpUtil.getResponse("ok"), httpResponse);
    }


}
