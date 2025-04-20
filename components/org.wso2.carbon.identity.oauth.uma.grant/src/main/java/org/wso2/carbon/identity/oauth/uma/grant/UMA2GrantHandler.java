/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth.uma.grant;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.uma.common.exception.UMAClientException;
import org.wso2.carbon.identity.oauth.uma.common.exception.UMAServerException;
import org.wso2.carbon.identity.oauth.uma.grant.connector.PolicyEvaluator;
import org.wso2.carbon.identity.oauth.uma.grant.internal.UMA2GrantServiceComponent;
import org.wso2.carbon.identity.oauth.uma.permission.service.dao.PermissionTicketDAO;
import org.wso2.carbon.identity.oauth.uma.permission.service.model.Resource;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.ResponseHeader;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AccessTokenRespDTO;
import org.wso2.carbon.identity.oauth2.model.RequestParameter;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.identity.oauth2.token.handlers.grant.AbstractAuthorizationGrantHandler;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.security.interfaces.RSAPrivateKey;
import java.text.ParseException;
import java.util.List;

/**
 * Grant type for User Managed Access 2.0.
 */
public class UMA2GrantHandler extends AbstractAuthorizationGrantHandler {

    private static final Log log = LogFactory.getLog(UMA2GrantHandler.class);

    @Override
    public boolean validateGrant(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        String grantType = null;
        String idToken = null;
        String permissionTicket = null;

        // Extract request parameters.
        RequestParameter[] parameters = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getRequestParameters();

        for (RequestParameter parameter : parameters) {

            // Extract grant type.
            if (UMAGrantConstants.GRANT_PARAM.equals(parameter.getKey())) {
                if (parameter.getValue() != null) {
                    grantType = parameter.getValue()[0];
                }
            }

            // Extract permission ticket.
            if (UMAGrantConstants.PERMISSION_TICKET.equals(parameter.getKey())) {
                if (parameter.getValue() != null) {
                    permissionTicket = parameter.getValue()[0];
                }
            }

            // Extract ID token.
            if (UMAGrantConstants.CLAIM_TOKEN.equals(parameter.getKey())) {
                if (parameter.getValue() != null) {
                    idToken = parameter.getValue()[0];
                }
            }
        }

        if (StringUtils.isEmpty(grantType) || !StringUtils.equals(UMAGrantConstants.UMA_GRANT_TYPE, grantType)) {
            return false;
        }

        if (StringUtils.isEmpty(permissionTicket)) {
            throw new IdentityOAuth2Exception("Empty permission ticket.");
        }

        if (StringUtils.isEmpty(idToken)) {
            throw new IdentityOAuth2Exception("Empty id-token.");
        }

        String applicationTenantDomain = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getTenantDomain();
        validateRequestTenantDomain(applicationTenantDomain, tokReqMsgCtx.getOauth2AccessTokenReqDTO().getClientId());
        String subject = getSubjectFromIDToken(idToken, applicationTenantDomain);
        boolean isValidTicket;

        FrameworkUtils.startTenantFlow(applicationTenantDomain);
        try {
            // Validate the permission ticket against the subject.
            isValidTicket = validatePermissionTicket(permissionTicket, subject);
        } finally {
            FrameworkUtils.endTenantFlow();
        }

        if (isValidTicket) {
            AuthenticatedUser authenticatedUser = new AuthenticatedUser();
            authenticatedUser.setUserName(subject);
            String tenantDomain = extractTenantDomainFromSubject(subject);
            if (tenantDomain != null) {
                authenticatedUser.setTenantDomain(tenantDomain);
            } else {
                authenticatedUser.setTenantDomain(applicationTenantDomain);
            }
            authenticatedUser.setUserStoreDomain(IdentityUtil.extractDomainFromName(subject));
            tokReqMsgCtx.setAuthorizedUser(authenticatedUser);
            tokReqMsgCtx.setScope(tokReqMsgCtx.getOauth2AccessTokenReqDTO().getScope());
            return true;
        } else {
            ResponseHeader responseHeader = new ResponseHeader();
            responseHeader.setKey(UMAGrantConstants.ERROR_RESPONSE_HEADER);
            responseHeader.setValue("Failed validation for the permission ticket for the given user.");
            tokReqMsgCtx.addProperty(UMAGrantConstants.RESPONSE_HEADERS, new ResponseHeader[]{responseHeader});
            return false;
        }
    }

    @Override
    public OAuth2AccessTokenRespDTO issue(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        validateRequestTenantDomain(tokReqMsgCtx.getOauth2AccessTokenReqDTO().getTenantDomain(), tokReqMsgCtx
                .getOauth2AccessTokenReqDTO().getClientId());
        OAuth2AccessTokenRespDTO oAuth2AccessTokenRespDTO = super.issue(tokReqMsgCtx);
        String tokenId = oAuth2AccessTokenRespDTO.getTokenId();

        // Extract request parameters.
        RequestParameter[] parameters = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getRequestParameters();

        String permissionTicket = null;
        for (RequestParameter parameter : parameters) {
            // Extract permission ticket.
            if (UMAGrantConstants.PERMISSION_TICKET.equals(parameter.getKey())) {
                if (parameter.getValue() != null) {
                    permissionTicket = parameter.getValue()[0];
                }
            }
        }

        String clientId = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getClientId();
        if (StringUtils.isEmpty(permissionTicket)) {
            throw new IdentityOAuth2Exception("Permission ticket is not available in the oauth token request " +
                                              "message context for client ID: " + clientId);
        }

        try {
            PermissionTicketDAO.saveTokenIdAgainstPermissionTicket(tokenId, permissionTicket);
        } catch (UMAServerException e) {
            throw new IdentityOAuth2Exception("Error occurred while issuing access token for client ID: " + clientId,
                                              e);
        }

        return oAuth2AccessTokenRespDTO;
    }

    /**
     * Validate the permission ticket against the subject.
     * @param permissionTicket Permission ticket.
     * @param subject Subject identifier.
     * @return True if validation is successful.
     * @throws IdentityOAuth2Exception
     */
    private boolean validatePermissionTicket(String permissionTicket, String subject) throws IdentityOAuth2Exception {

        List<Resource> resources;

        try {
            resources = PermissionTicketDAO.validatePermissionTicket(permissionTicket);
            for (PolicyEvaluator policyEvaluator : UMA2GrantServiceComponent.getPolicyEvaluators()) {
                if (!policyEvaluator.isAuthorized(subject, resources)) {
                    return false;
                }
            }
            return true;
        } catch (UMAClientException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error while requesting Requesting Party Token (RPT). Invalid permission " +
                        "ticket: " + permissionTicket, e);
            }
            return false;
        } catch (UMAServerException e) {
            log.error("Server error occurred while validating permission ticket.", e);
            return false;
        }
    }

    private String getSubjectFromIDToken(String idToken, String tenantDomain) throws IdentityOAuth2Exception {

        JWTClaimsSet claimsSet = null;

        if (StringUtils.isEmpty(tenantDomain)) {
            tenantDomain = MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
        }

        // Check whether the assertion is encrypted.
        EncryptedJWT encryptedJWT = getEncryptedJWT(idToken);
        if (encryptedJWT != null) {
            RSAPrivateKey rsaPrivateKey = (RSAPrivateKey) OAuth2Util.getPrivateKey(tenantDomain);
            RSADecrypter decrypter = new RSADecrypter(rsaPrivateKey);
            try {
                encryptedJWT.decrypt(decrypter);
            } catch (JOSEException e) {
                throw new IdentityOAuth2Exception("Error while decrypting the encrypted JWT.", e);
            }
            try {
                // If the assertion is a nested JWT.
                String payload;
                if (encryptedJWT.getPayload() != null) {
                    payload = encryptedJWT.getPayload().toString();
                } else {
                    throw new IdentityOAuth2Exception("Empty payload in the encrypted JWT.");
                }

                // Check whether the encrypted JWT is signed.
                if (isEncryptedJWTSigned(payload)) {
                    SignedJWT signedJWT = SignedJWT.parse(payload);
                    claimsSet = signedJWT.getJWTClaimsSet();
                    if (log.isDebugEnabled()) {
                        log.debug("The encrypted JWT is signed. Obtained the claim set of the encrypted JWT.");
                    }
                } else {
                    try {
                        // If encrypted JWT is not signed.
                        claimsSet = encryptedJWT.getJWTClaimsSet();
                        if (log.isDebugEnabled()) {
                            log.debug("The encrypted JWT is not signed. Obtained the claim set of the encrypted JWT.");
                        }
                    } catch (ParseException ex) {
                        throw new IdentityOAuth2Exception("Error when trying to retrieve claimsSet from the " +
                                "encrypted JWT.", ex);
                    }
                }
            } catch (ParseException e) {
                throw new IdentityOAuth2Exception("Unexpected number of Base64URL parts of the nested JWT payload. " +
                        "Expected number of parts must be three.", e);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("The assertion is not encrypted.");
            }

            // The assertion is not an encrypted one.
            SignedJWT signedJWT = getSignedJWT(idToken);
            try {
                claimsSet = signedJWT.getJWTClaimsSet();
            } catch (ParseException e) {
                throw new IdentityOAuth2Exception("Error while retrieving claims set from the signed JWT.", e);
            }
        }

        return claimsSet.getSubject();
    }

    private EncryptedJWT getEncryptedJWT(String idToken) {

        try {
            return EncryptedJWT.parse(idToken);
        } catch (ParseException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error while parsing the assertion. The assertion is not encrypted.");
            }
            return null;
        }
    }

    private SignedJWT getSignedJWT(String idToken) throws IdentityOAuth2Exception {

        try {
            return SignedJWT.parse(idToken);
        } catch (ParseException e) {
            String errorMessage = "Error while parsing the JWT.";
            throw new IdentityOAuth2Exception(errorMessage, e);
        }
    }

    private boolean isEncryptedJWTSigned(String payload) {

        if (StringUtils.isNotEmpty(payload)) {
            String[] parts = payload.split(".");
            return parts.length == 3 && StringUtils.isNotEmpty(parts[2]);
        }
        return false;
    }

    private String extractTenantDomainFromSubject(String subject) {

        String tenantDomain = null;
        if (subject.contains("@") && !MultitenantUtils.isEmailUserName()) {
            tenantDomain = subject.substring(subject.lastIndexOf("@") + 1);
        } else if (MultitenantUtils.isEmailUserName() && subject.indexOf("@") != subject.lastIndexOf("@")) {
            tenantDomain = subject.substring(subject.lastIndexOf("@") + 1);
        }
        return tenantDomain;
    }

    /**
     * Validates whether the tenant domain set in context matches with the application's tenant domain in tenant
     * qualified URL mode.
     *
     * @param tenantDomainOfApp Tenant domain of the application.
     * @param consumerKey   Consumer key of oauth application.
     * @throws IdentityOAuth2Exception
     */
    private void validateRequestTenantDomain(String tenantDomainOfApp, String consumerKey) throws
            IdentityOAuth2Exception {

        if (IdentityTenantUtil.isTenantQualifiedUrlsEnabled()) {
            // In tenant qualified URL mode we would always have the tenant domain in the context.
            String tenantDomainFromContext = IdentityTenantUtil.getTenantDomainFromContext();
            if (!StringUtils.equals(tenantDomainFromContext, tenantDomainOfApp)) {
                // This means the tenant domain sent in the request and app's tenant domain do not match.
                throw new IdentityOAuth2Exception("A valid application cannot be found for the consumer key: '" +
                        consumerKey + "' in tenant domain: " + tenantDomainFromContext);
            }
        }
    }
}
