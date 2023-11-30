/**
 * Catena-X - Product Passport Consumer Frontend
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
 */

import { REDIRECT_URI, INIT_OPTIONS, BPN_CHECK, BPN } from "@/services/service.const";
import Keycloak from 'keycloak-js';
import authUtil from "@/utils/authUtil";
export default class Authentication {
    constructor() {
      this.keycloak = new Keycloak(INIT_OPTIONS);
    }
    keycloakInit(app) {
      var authProperties = app.config.globalProperties.$authProperties;
      this.keycloak.init({ onLoad: INIT_OPTIONS.onLoad }).then((auth) => {
        if (!auth) {
          window.location.reload();
        }
        else {
          console.log(this.keycloak.token);
          console.log(this.keycloak.tokenParsed);
          console.log(BPN_CHECK);
          authProperties.loginReachable = true;
          
          if(!BPN_CHECK || authUtil.checkBpn(this.keycloak.tokenParsed, BPN)){
            authProperties.isAuthorized = true;
          }

          console.log("Authorized: "+ authProperties.isAuthorized);
          console.log("Login: " + authProperties.loginReachable)
          console.log("Bpn Allowed: " + authUtil.checkBpn(this.keycloak.tokenParsed, BPN));
        }
        //Token Refresh
        setInterval(() => {
          this.updateToken(60);
        }, 60000);
        app.config.globalProperties.$authProperties = authProperties;
        app.mount('#app');
      }).catch((e) => {
        console.log(e);
        authProperties.loginReachable = false;
        authProperties.isAuthorized = false;
        app.config.globalProperties.$authProperties = authProperties;
        app.mount('#app');
      });
    }
    getAccessToken() {
      return this.keycloak.token;
    }

    getRefreshedToken() {
      return this.keycloak.refreshToken;
    }

    updateToken(minimumValidity) {
      this.keycloak.updateToken(minimumValidity).then((refreshed) => {
        if (refreshed) {
          console.info('Token refreshed ' + refreshed);
        } else {
          console.warn('Token not refreshed, valid for '
                    + Math.round(this.keycloak.tokenParsed.exp + this.keycloak.timeSkew - new Date().getTime() / 1000) + ' seconds');
        }
      }).catch(() => {
        console.error("updateToken -> Failed to refresh token");
      });
    }

    isUserAuthenticated() {
      return this.keycloak.authenticated;
    }
    getClientId() {
      return this.keycloak.clientId;
    }
    decodeAccessToken() {
      return authUtil.decodeToken(this.keycloak.token);
    }
    getUserName() {
      return this.decodeAccessToken().email;
    }
    getName() {
      return this.decodeAccessToken().name;
    }
    getBpn() {
      return this.decodeAccessToken().bpn;
    }
    getSessionId() {
      return this.keycloak.sessionId;
    }
    getRole() {
      let clientRoles = '';
      clientRoles = this.keycloak.resourceAccess[this.getClientId()].roles;
      return clientRoles.length == 1 ? clientRoles[0] : clientRoles;
    }
    logout() {
      let logoutOptions = { redirectUri: REDIRECT_URI };
      this.keycloak.logout(logoutOptions).then((success) => {
        console.log("--> log: logout success ", success);
      }).catch((error) => {
        console.log("--> log: logout error ", error);
      });
    }
}
