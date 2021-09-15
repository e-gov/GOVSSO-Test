package ee.ria.govsso

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEException
import com.nimbusds.jwt.SignedJWT
import io.qameta.allure.Allure
import io.qameta.allure.Step
import io.restassured.response.Response
import org.hamcrest.Matchers
import org.spockframework.lang.Wildcard

import java.text.ParseException

import static org.hamcrest.CoreMatchers.is
import static org.hamcrest.Matchers.anyOf
import static org.hamcrest.Matchers.containsString
import static org.hamcrest.Matchers.equalTo

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.hamcrest.MatcherAssert.assertThat


class Steps {

    @Step("Initialize authentication sequence in OIDC service with params")
    static Response startAuthenticationInOidcWithParams(FlowTara flow, Map<String, String> paramsMap) {
        Response initSession = Requests.getRequestWithParams(flow, flow.oidcService.fullAuthenticationRequestUrl, paramsMap, Collections.emptyMap())
        String authCookie = initSession.getCookie("oauth2_authentication_csrf")
        Utils.setParameter(flow.oidcService.cookies, "oauth2_authentication_csrf", authCookie)
        flow.setLoginChallenge(Utils.getParamValueFromResponseHeader(initSession, "login_challenge"))
        return initSession
    }

    @Step("Initialize authentication sequence in OIDC service with defaults")
    static Response startAuthenticationInOidc(FlowTara flow) {
        Map<String, String> paramsMap = OpenIdUtils.getAuthorizationParameters(flow)
        return Steps.startAuthenticationInOidcWithParams(flow, paramsMap)
    }

    @Step("Initialize authentication sequence in login service")
    static Response createLoginSession(FlowTara flow, Response response) {
        Response initLogin = followRedirect(flow, response)
        flow.setSessionId(initLogin.getCookie("SESSION"))
        flow.setLogin_locale(initLogin.getCookie("LOGIN_LOCALE"))
        if (initLogin.body().prettyPrint().contains("_csrf")) {
            flow.setCsrf(initLogin.body().htmlPath().get("**.find {it.@name == '_csrf'}.@value"))
        }
        return initLogin
    }

    @Step("Start authentication in TARA and follow redirects")
    static Response startAuthenticationInTara(FlowTara flow, String scopeList = "openid", String login_locale = "et") {
        Map<String, String> paramsMap = OpenIdUtils.getAuthorizationParameters(flow, scopeList, login_locale)
        Response initOIDCServiceSession = startAuthenticationInOidcWithParams(flow, paramsMap)
        return createLoginSession(flow, initOIDCServiceSession)
    }

    @Step("Polling Mobile-ID authentication response")
    static Response pollMidResponse(FlowTara flow, long pollingIntevalMillis = 2000L) {
        int counter = 0
        Response response = null
        while (counter < 12) {
            sleep(pollingIntevalMillis)
            response = Requests.pollMid(flow)
            if (response.body().jsonPath().get("status") != "PENDING") {
                break
            }
            ++counter
        }
        return response
    }

    @Step("Authenticate with Mobile-ID")
    static Response authenticateWithMid(FlowTara flow, String idCode, String phoneNo) {
        Requests.startMidAuthentication(flow, idCode, phoneNo)
        pollMidResponse(flow)
        Response acceptResponse = Requests.postRequestWithSessionId(flow, flow.loginService.fullAuthAcceptUrl)
        Response oidcServiceResponse = Steps.getOAuthCookies(flow, acceptResponse)
        return followRedirectWithSessionId(flow, oidcServiceResponse)
    }

    @Step("Authenticate with Smart-ID")
    static Response authenticateWithSid(FlowTara flow, String idCode) {
        initSidAuthSession(flow, flow.sessionId, idCode, Collections.emptyMap())
        pollSidResponse(flow)
        Response acceptResponse = Requests.postRequestWithSessionId(flow, flow.loginService.fullAuthAcceptUrl)
        Response oidcServiceResponse = getOAuthCookies(flow, acceptResponse)
        return followRedirectWithSessionId(flow, oidcServiceResponse)
    }

    @Step("Authenticate with ID-Card")
    static Response authenticateWithIdCard(FlowTara flow, String certificateFileName) {
        String certificate = Utils.getCertificateAsString(certificateFileName)
        HashMap<String, String> headersMap = (HashMap) Collections.emptyMap()
        Utils.setParameter(headersMap, "XCLIENTCERTIFICATE", certificate)
        Requests.idCardAuthentication(flow, headersMap)
        Response acceptResponse = Requests.postRequestWithSessionId(flow, flow.loginService.fullAuthAcceptUrl)
        Response oidcServiceResponse = getOAuthCookies(flow, acceptResponse)
        return followRedirectWithSessionId(flow, oidcServiceResponse)

    }

    @Step("Initialize Smart-ID authentication session")
    static Response initSidAuthSession(FlowTara flow, String sessionId
                                       , Object idCode
                                       , Map additionalParamsMap = Collections.emptyMap()) {
        LinkedHashMap<String, String> formParamsMap = (LinkedHashMap) Collections.emptyMap()
        Utils.setParameter(formParamsMap, "_csrf", flow.csrf)
        if (!(idCode instanceof Wildcard)) {
            Utils.setParameter(formParamsMap, "idCode", idCode)
        }
        HashMap<String, String> cookieMap = (HashMap) Collections.emptyMap()
        Utils.setParameter(cookieMap, "SESSION", sessionId)
        Utils.setParameter(cookieMap, "LOGIN_LOCALE", flow.login_locale)
        return Requests.postRequestWithCookiesAndParams(flow, flow.loginService.fullSidInitUrl, cookieMap, formParamsMap, additionalParamsMap)
    }

    @Step("Polling Smart-ID authentication response")
    static Response pollSidResponse(FlowTara flow, long pollingIntevalMillis = 2000L) {
        int counter = 0
        Response response = null
        while (counter < 20) {
            response = Requests.pollSid(flow)
            if (response.body().jsonPath().get("status") != "PENDING") {
                break
            }
            ++counter
            sleep(pollingIntevalMillis)
        }
        return response
    }


    @Step("Getting OAuth2 cookies")
    static Response getOAuthCookies(flow, Response response) {
        Response oidcServiceResponse = followRedirectWithCookies(flow, response, flow.oidcService.cookies)
        Utils.setParameter(flow.oidcService.cookies, "oauth2_consent_csrf", oidcServiceResponse.getCookie("oauth2_consent_csrf"))
        return oidcServiceResponse
    }

    @Step("Follow redirect")
    static Response followRedirect(FlowTara flow, Response response) {
        String location = response.then().extract().response().getHeader("location")
        return Requests.followRedirect(flow, location)
    }

    @Step("Follow redirect with cookies")
    static Response followRedirectWithCookies(FlowTara flow, Response response, Map cookies) {
        String location = response.then().extract().response().getHeader("location")
        return Requests.followRedirectWithCookie(flow, location, cookies)
    }

    @Step("Follow redirect with session id")
    static Response followRedirectWithSessionId(FlowTara flow, Response response) {
        String location = response.then().extract().response().getHeader("location")
        return Requests.getRequestWithSessionId(flow, location)
    }

    @Step("Confirm or reject consent")
    static Response submitConsent(FlowTara flow, boolean consentGiven) {
        HashMap<String, String> cookiesMap = (HashMap) Collections.emptyMap()
        Utils.setParameter(cookiesMap, "SESSION", flow.sessionId)
        HashMap<String, String> formParamsMap = (HashMap) Collections.emptyMap()
        Utils.setParameter(formParamsMap, "consent_given", consentGiven)
        Utils.setParameter(formParamsMap, "_csrf", flow.csrf)
        return Requests.postRequestWithCookiesAndParams(flow, flow.loginService.fullConsentConfirmUrl, cookiesMap, formParamsMap, Collections.emptyMap())
    }

    @Step("Confirm or reject consent and finish authentication process")
    static Response submitConsentAndFollowRedirects(FlowTara flow, boolean consentGiven, Response consentResponse) {
        if (consentResponse.getStatusCode().toInteger() == 200) {
            consentResponse = submitConsent(flow, consentGiven)
        }
        return followRedirectWithCookies(flow, consentResponse, flow.oidcService.cookies)
    }

    @Step("Get identity token")
    static Response getIdentityTokenResponse(FlowTara flow, Response response) {
        String authorizationCode = Utils.getParamValueFromResponseHeader(response, "code")
        return Requests.getWebToken(flow, authorizationCode)
    }

    @Step("verify token")
    static SignedJWT verifyTokenAndReturnSignedJwtObject(FlowTara flow, String token) throws ParseException, JOSEException, IOException {
        SignedJWT signedJWT = SignedJWT.parse(token)
        addJsonAttachment("Header", signedJWT.getHeader().toString())
        addJsonAttachment("Payload", signedJWT.getJWTClaimsSet().toString())
        try {
            Allure.link("View Token in jwt.io", new io.qameta.allure.model.Link().toString(),
                    "https://jwt.io/#debugger-io?token=" + token)
        } catch (Exception e) {
            //NullPointerException when running test from IntelliJ
        }
        assertThat("Token Signature is not valid!", OpenIdUtils.isTokenSignatureValid(flow.jwkSet, signedJWT), is(true))
        assertThat(signedJWT.getJWTClaimsSet().getAudience().get(0), equalTo(flow.oidcClient.clientId))
        assertThat(signedJWT.getJWTClaimsSet().getIssuer(), equalTo(flow.openIdServiceConfiguration.get("issuer")))
        Date date = new Date()
        assertThat("Expected current: " + date + " to be before exp: " + signedJWT.getJWTClaimsSet().getExpirationTime(), date.before(signedJWT.getJWTClaimsSet().getExpirationTime()), is(true))
        assertThat("Expected current: " + date + " to be after nbf: " + signedJWT.getJWTClaimsSet().getNotBeforeTime(), date.after(signedJWT.getJWTClaimsSet().getNotBeforeTime()), is(true))
        if (!flow.getNonce().isEmpty()) {
            assertThat(signedJWT.getJWTClaimsSet().getStringClaim("nonce"), equalTo(flow.getNonce()))
        }
        assertThat(signedJWT.getJWTClaimsSet().getStringClaim("state"), equalTo(flow.getState()))
        return signedJWT
    }

    @Step("verify response headers")
    static void verifyResponseHeaders(Response response) {
        assertThat(response.getHeader("X-Frame-Options"), equalTo("DENY"))
        String policyString = "connect-src 'self'; default-src 'none'; font-src 'self'; img-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; block-all-mixed-content"
        assertThat(response.getHeader("Content-Security-Policy"), equalTo(policyString))
        assertThat(response.getHeader("Strict-Transport-Security"), anyOf(containsString("max-age=16070400"), containsString("max-age=31536000")))
        assertThat(response.getHeader("Strict-Transport-Security"), containsString("includeSubDomains"))
        assertThat(response.getHeader("Cache-Control"), equalTo("no-cache, no-store, max-age=0, must-revalidate"))
        assertThat(response.getHeader("X-Content-Type-Options"), equalTo("nosniff"))
        assertThat(response.getHeader("X-XSS-Protection"), equalTo("1; mode=block"))
    }

    @Step("Authenticate with MID in TARA")
    static Response authenticateWithMidInTARA(FlowTara flow, String idCode, String phoneNo) {
        //TODO: This should be replaced with receiving URL from session service and following redirects. Enable automatic redirect following for this?
        Steps.startAuthenticationInTara(flow)

        //This should be ok as is
        Response midAuthResponse = Steps.authenticateWithMid(flow,idCode, phoneNo)

        //TODO: Enable automatic redirect following for this?
        return Steps.submitConsentAndFollowRedirects(flow, true, midAuthResponse)
    }

    @Step("Authenticate with SID in TARA")
    static Response authenticateWithSidInTARA(FlowTara flow, String idCode) {
        Steps.startAuthenticationInTara(flow, "openid smartid")
        Response sidAuthResponse = Steps.authenticateWithSid(flow,idCode)
        return Steps.submitConsentAndFollowRedirects(flow, true, sidAuthResponse)
    }

    @Step("Authenticate with ID-Card in TARA")
    static Response authenticateWithIdCardInTARA(FlowTara flow) {
        String certificate = Utils.getCertificateAsString("src/test/resources/joeorg-auth.pem")
        Steps.startAuthenticationInTara(flow)
        HashMap<String, String> headersMap = (HashMap) Collections.emptyMap()
        Utils.setParameter(headersMap, "XCLIENTCERTIFICATE", certificate)
        Requests.idCardAuthentication(flow, headersMap)
        Response acceptResponse = Requests.postRequestWithSessionId(flow, flow.loginService.fullAuthAcceptUrl)
        Response oidcServiceResponse = Steps.getOAuthCookies(flow, acceptResponse)
        Response consentResponse = Steps.followRedirectWithSessionId(flow, oidcServiceResponse)

        if (consentResponse.getStatusCode() == 200) {
            consentResponse = Steps.submitConsent(flow, true)
        }

        return Steps.followRedirectWithCookies(flow, consentResponse, flow.oidcService.cookies)
    }

    @Step("Authenticate with eIDAS in TARA")
    static Response authenticateWithEidasInTARA(FlowTara flow, String country, String username, String password, String loa) {
        //TODO: This should be replaced with receiving URL from session service and following redirects.
        Steps.startAuthenticationInTara(flow, "openid eidas")
        Response initEidasAuthenticationSession = EidasSteps.initEidasAuthSession(flow, flow.sessionId, country, Collections.emptyMap())
        flow.setNextEndpoint(initEidasAuthenticationSession.body().htmlPath().getString("**.find { form -> form.@method == 'post' }.@action"))
        flow.setRelayState(initEidasAuthenticationSession.body().htmlPath().getString("**.find { input -> input.@name == 'RelayState' }.@value"))
        flow.setRequestMessage(initEidasAuthenticationSession.body().htmlPath().getString("**.find { input -> input.@name == 'SAMLRequest' }.@value"))
        Response colleagueResponse = EidasSteps.continueEidasAuthenticationFlow(flow, username, password, loa)
        Response authorizationResponse = EidasSteps.getAuthorizationResponseFromEidas(flow, colleagueResponse)
        Response redirectionResponse = EidasSteps.eidasRedirectAuthorizationResponse(flow, authorizationResponse)
        Response acceptResponse = EidasSteps.eidasAcceptAuthorizationResult(flow, redirectionResponse)
        Response oidcServiceResponse = Steps.getOAuthCookies(flow, acceptResponse)
        Response redirectResponse = Steps.followRedirectWithSessionId(flow, oidcServiceResponse)
        return Steps.submitConsentAndFollowRedirects(flow, true, redirectResponse)
    }

    private static void addJsonAttachment(String name, String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper()
        Object jsonObject = mapper.readValue(json, Object.class)
        String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)
        Allure.addAttachment(name, "application/json", prettyJson, "json")
    }
}
