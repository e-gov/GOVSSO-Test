package ee.ria.govsso

import com.nimbusds.jose.jwk.JWKSet
import io.qameta.allure.Feature
import io.restassured.filter.cookie.CookieFilter
import io.restassured.response.Response
import org.hamcrest.Matchers

import static org.junit.jupiter.api.Assertions.*
import static org.hamcrest.MatcherAssert.assertThat

//TODO: Transferred tests from TARA2 project for preliminary usage
class OidcRedirectRequestSpec extends GovSsoSpecification {
    Flow flow = new Flow(props)

    def setup() {
        flow.cookieFilter = new CookieFilter()
        flow.openIdServiceConfiguration = Requests.getOpenidConfiguration(flow.oidcService.fullConfigurationUrl)
        flow.jwkSet = JWKSet.load(Requests.getOpenidJwks(flow.oidcService.fullJwksUrl))
    }

    @Feature("")
    def "Verify redirection url parameters"() {
        expect:
        Steps.startAuthenticationInTara(flow)
        Response midAuthResponse = Steps.authenticateWithMid(flow,"60001017716", "69100366")
        Response response = Steps.submitConsentAndFollowRedirects(flow, true, midAuthResponse)
        assertEquals(302, response.statusCode(), "Correct HTTP status code is returned")
        assertTrue(Utils.getParamValueFromResponseHeader(response, "code").size() > 60, "Code parameter exists")
        assertEquals(flow.state, Utils.getParamValueFromResponseHeader(response, "state"), "Correct state parameter")
    }

    @Feature("")
    def "Verify redirection url with invalid scope"() {
        expect:
        Map<String, String> paramsMap = OpenIdUtils.getAuthorizationParameters(flow, "my_scope", "et")
        Response response = Steps.startAuthenticationInOidcWithParams(flow, paramsMap)

        assertEquals(302, response.statusCode(), "Correct HTTP status code is returned")
        assertEquals(flow.state, Utils.getParamValueFromResponseHeader(response, "state"), "Correct state parameter")
        assertEquals("invalid_scope", Utils.getParamValueFromResponseHeader(response, "error"), "Error parameter exists")
        assertThat("Error description parameter exists", Utils.getParamValueFromResponseHeader(response, "error_description") , Matchers.startsWith("The requested scope is invalid"))
    }

    @Feature("")
    def "Verify redirection url with invalid state"() {
        expect:
        Map<String, String> paramsMap = OpenIdUtils.getAuthorizationParameters(flow)
        paramsMap.put("state", "ab")
        Response response = Steps.startAuthenticationInOidcWithParams(flow, paramsMap)
        assertEquals(302, response.statusCode(), "Correct HTTP status code is returned")
        assertEquals("ab", Utils.getParamValueFromResponseHeader(response, "state"), "Correct state parameter")
        assertEquals("invalid_state", Utils.getParamValueFromResponseHeader(response, "error"), "Error parameter exists")
        assertThat("Error description parameter exists", Utils.getParamValueFromResponseHeader(response, "error_description") , Matchers.startsWith("The state is missing"))
    }

    @Feature("")
    def "Verify redirection url with unsupported response type"() {
        expect:
        Map<String, String> paramsMap = OpenIdUtils.getAuthorizationParameters(flow)
        paramsMap.put("response_type", "token")
        Response response = Steps.startAuthenticationInOidcWithParams(flow, paramsMap)
        assertEquals(302, response.statusCode(), "Correct HTTP status code is returned")
        assertEquals(flow.state, Utils.getParamValueFromResponseHeader(response, "state"), "Correct state parameter")
        assertEquals("unsupported_response_type", Utils.getParamValueFromResponseHeader(response, "error"), "Error parameter exists")
        assertThat("Error description parameter exists", Utils.getParamValueFromResponseHeader(response, "error_description") , Matchers.startsWith("The authorization server does not support"))
    }

    @Feature("")
    def "Verify redirection url with user cancel"() {
        expect:
        Steps.startAuthenticationInTara(flow)
        HashMap<String, String> paramsMap = (HashMap) Collections.emptyMap()
        def map1 = Utils.setParameter(paramsMap, "error_code", REJECT_ERROR_CODE)
        HashMap<String, String> cookieMap = (HashMap) Collections.emptyMap()
        def map3 = Utils.setParameter(cookieMap, "SESSION", flow.taraLoginService.sessionId)
        Response rejectResponse = Requests.getRequestWithCookiesAndParams(flow, flow.taraLoginService.fullAuthRejectUrl, cookieMap, paramsMap, Collections.emptyMap())
        Response response = Steps.followRedirectWithCookies(flow, rejectResponse, flow.oidcService.cookies)
        assertEquals(302, response.statusCode(), "Correct HTTP status code is returned")
        assertEquals(flow.state, Utils.getParamValueFromResponseHeader(response, "state"), "Correct state parameter")
        assertEquals("user_cancel", Utils.getParamValueFromResponseHeader(response, "error"), "Error parameter exists")
        assertThat("Error description parameter exists", Utils.getParamValueFromResponseHeader(response, "error_description") , Matchers.startsWith("User canceled the authentication process"))
    }

}