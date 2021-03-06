package ee.ria.govsso

import com.nimbusds.jose.jwk.JWKSet
import io.qameta.allure.Feature
import io.restassured.filter.cookie.CookieFilter
import io.restassured.response.Response
import spock.lang.Unroll

import static org.hamcrest.Matchers.*
import static org.hamcrest.MatcherAssert.assertThat

class UserInterfaceSpec extends GovSsoSpecification {

    Flow flow = new Flow(props)

    def setup() {
        flow.cookieFilter = new CookieFilter()
        flow.openIdServiceConfiguration = Requests.getOpenidConfiguration(flow.ssoOidcService.fullConfigurationUrl)
        flow.jwkSet = JWKSet.load(Requests.getOpenidJwks(flow.ssoOidcService.fullJwksUrl))
    }

    @Unroll
    @Feature("LOGIN_INIT_VIEW")
    def "Correct buttons with correct form actions exist in session continuation display with specified ui_locales: #uiLocale"() {
        expect:
        Steps.authenticateWithIdCardInGovssoWithUiLocales(flow, uiLocale)
        Response oidcAuth = Steps.startAuthenticationInSsoOidc(flow, flow.oidcClientB.clientId, flow.oidcClientB.fullResponseUrl)
        Response initLogin = Steps.followRedirect(flow, oidcAuth)

        String buttonContinueSession = initLogin.body().htmlPath().getString("**.find { button -> button.@formaction == '/login/continuesession'}")
        String buttonReauthenticate = initLogin.body().htmlPath().getString("**.find { button -> button.@formaction == '/login/reauthenticate'}")
        String buttonReturnToClient = initLogin.body().htmlPath().getString("**.find { button -> button.@formaction == '/login/reject'}")
        assertThat("Continue button exists with correct form action", buttonContinueSession, is(continueButton))
        assertThat("Reauthenticate button exists with correct form action", buttonReauthenticate, is(reauthenticateButton))
        assertThat("Return to service provider link exists with correct form action", buttonReturnToClient, is(returnButton))
        assertThat("Correct logo", initLogin.body().asString().contains(Utils.getFileAsString("src/test/resources/base64_client_B_logo")))

        where:
        uiLocale | continueButton     | reauthenticateButton | returnButton
        "et"     | "J??tka seanssi"    | "Autendi uuesti"     | "Tagasi teenusepakkuja juurde"
        "en"     | "Continue session" | "Re-authenticate"    | "Return to service provider"
        "ru"     | "???????????????????? ??????????" | "?????????? ??????????"        | "?????????????????? ?? ???????????????????? ??????????"
    }

    @Unroll
    @Feature("LOGOUT_INIT_VIEW")
    def "Correct buttons with correct form actions exist in session logout display with specified ui_locales: #uiLocale"() {
        expect:
        Steps.authenticateWithIdCardInGovssoWithUiLocales(flow, uiLocale)
        Response continueSession = Steps.continueWithExistingSession(flow, flow.oidcClientB.clientId, flow.oidcClientB.clientSecret, flow.oidcClientB.fullResponseUrl)
        String idToken = continueSession.jsonPath().get("id_token")

        Response oidcLogout = Steps.startLogout(flow, idToken, flow.oidcClientB.fullBaseUrl)
        Response initLogout = Steps.followRedirect(flow, oidcLogout)

        String buttonEndSession = initLogout.body().htmlPath().getString("**.find { button -> button.@formaction == '/logout/endsession'}")
        String buttonContinueSession = initLogout.body().htmlPath().getString("**.find { button -> button.@formaction == '/logout/continuesession'}")
        assertThat("Reauthenticate button exists with correct form action", buttonEndSession, is(endButton))
        assertThat("Continue button exists with correct form action", buttonContinueSession, is(continueButton))
        assertThat("Correct logo", initLogout.body().asString().contains(Utils.getFileAsString("src/test/resources/base64_client_B_logo")))

        where:
        uiLocale | endButton              | continueButton
        "et"     | "Logi k??ikidest v??lja" | "J??tka seanssi"
        "en"     | "Log out all"          | "Continue session"
        "ru"     | "?????????? ???? ???????? ??????????"  | "???????????????????? ??????????"
    }

    @Unroll
    @Feature("LOGIN_INIT_VIEW")
    def "Correct translations used in session continuation display: translation #uiLocale"() {
        expect:
        Steps.authenticateWithIdCardInGovssoWithUiLocales(flow, uiLocale)

        Response oidcAuth = Steps.startAuthenticationInSsoOidc(flow, flow.oidcClientB.clientId, flow.oidcClientB.fullResponseUrl)
        Response initLogin = Steps.followRedirect(flow, oidcAuth)

        initLogin.then().body("html.head.title", equalTo(title))

        where:
        uiLocale | title
        "et"     | "Riigi autentimisteenus - Turvaline autentimine asutuste e-teenustes"
        "en"     | "National authentication service - Secure authentication for e-services"
        "ru"     | "???????????????????????? ???????????? ???????????????????????????? - ?????? ???????????????????? ???????????????????????????? ?? ??-??????????????"
    }

    @Unroll
    @Feature("LOGOUT_INIT_VIEW")
    def "Correct translations used in session logout display: translation #uiLocale"() {
        expect:
        Steps.authenticateWithIdCardInGovssoWithUiLocales(flow, uiLocale)
        Response continueSession = Steps.continueWithExistingSession(flow, flow.oidcClientB.clientId, flow.oidcClientB.clientSecret, flow.oidcClientB.fullResponseUrl)
        String idToken = continueSession.jsonPath().get("id_token")

        Response oidcLogout = Steps.startLogout(flow, idToken, flow.oidcClientB.fullBaseUrl)
        Response initLogout = Steps.followRedirect(flow, oidcLogout)

        initLogout.then().body("html.head.title", equalTo(title))

        where:
        uiLocale | title
        "et"     | "Riigi autentimisteenus - Turvaline autentimine asutuste e-teenustes"
        "en"     | "National authentication service - Secure authentication for e-services"
        "ru"     | "???????????????????????? ???????????? ???????????????????????????? - ?????? ???????????????????? ???????????????????????????? ?? ??-??????????????"
    }

    //TODO: Improve logoutText assertion
    @Unroll
    @Feature("LOGOUT_INIT_VIEW")
    def "Correct logout client and active client displayed in logout display with specified ui_locales: #uiLocale"() {
        expect:
        Steps.authenticateWithIdCardInGovssoWithUiLocales(flow, uiLocale)
        Response continueSession = Steps.continueWithExistingSession(flow, flow.oidcClientB.clientId, flow.oidcClientB.clientSecret, flow.oidcClientB.fullResponseUrl)
        String idToken = continueSession.jsonPath().get("id_token")

        Response oidcLogout = Steps.startLogout(flow, idToken, flow.oidcClientB.fullBaseUrl)
        Response initLogout = Steps.followRedirect(flow, oidcLogout)

        assertThat("Correct logged out client", initLogout.body().htmlPath().getString("/c-tab-login/*}").contains(logoutText))
        assertThat("Correct active client", initLogout.body().htmlPath().getString("/c-tab-login/*}").contains(sessionText))
        assertThat("Correct logo", initLogout.body().asString().contains(Utils.getFileAsString("src/test/resources/base64_client_B_logo")))

        where:
        uiLocale | logoutText         | sessionText
        "et"     | "Teenusenimi B"    | "Olete j??tkuvalt sisse logitud j??rgnevatesse teenustesse:Teenusenimi A"
        "en"     | "Service name B"   | "You are still logged in to the following services:Service name A"
        "ru"     | "???????????????? ???????????? B"| "???? ???????????????????????? ?? ?????????????????? ??????????????:???????????????? ???????????? A"
    }

    @Unroll
    @Feature("LOGIN_INIT_VIEW")
    def "Correct user data displayed in session continuation display"() {
        expect:
        Steps.authenticateWithIdCardInGovsso(flow)
        Response oidcAuth = Steps.startAuthenticationInSsoOidc(flow, flow.oidcClientB.clientId, flow.oidcClientB.fullResponseUrl)
        Response initLogin = Steps.followRedirect(flow, oidcAuth)

        assertThat("Correct first name", initLogin.body().htmlPath().getString("/personal-info/*}").contains("JAAK-KRISTJAN"))
        assertThat("Correct surname", initLogin.body().htmlPath().getString("/personal-info/*}").contains("J??EORG"))
        assertThat("Correct personal code", initLogin.body().htmlPath().getString("/personal-info/*}").contains("EE38001085718"))
        assertThat("Correct date of birth", initLogin.body().htmlPath().getString("/personal-info/*}").contains("08.01.1980"))
        assertThat("Correct logo", initLogin.body().asString().contains(Utils.getFileAsString("src/test/resources/base64_client_B_logo")))
    }

    @Unroll
    @Feature("LOGIN_INIT_REDIRECT_TO_TARA")
    def "Correct GOVSSO client logo and service name displayed in TARA"() {
        expect:
        Response oidcAuth = Steps.startAuthenticationInSsoOidcWithDefaults(flow)
        Response initLogin = Steps.startSessionInSessionService(flow, oidcAuth)
        Response taraOidcAuth = Steps.followRedirect(flow, initLogin)
        Response taraInitLogin = Steps.followRedirect(flow, taraOidcAuth)

        assertThat("Correct service name", taraInitLogin.body().asString().contains("Teenusenimi A"))
        assertThat("Correct logo", taraInitLogin.body().asString().contains(Utils.getFileAsString("src/test/resources/base64_client_A_logo")))
    }

    @Feature("LOGIN_INIT_VIEW")
    def "Correct buttons with correct form actions exist in session continuation if original acr is lower than expected with specified ui_locales: #uiLocale"() {
        expect:
        Steps.authenticateWithEidasInGovssoWithUiLocales(flow, "substantial", "C", uiLocale)

        Response oidcAuth = Steps.startAuthenticationInSsoOidc(flow, flow.oidcClientB.clientId, flow.oidcClientB.fullResponseUrl)
        Response initLogin = Steps.followRedirect(flow, oidcAuth)

        String buttonBack = initLogin.body().htmlPath().getString("**.find { button -> button.@formaction == '/login/reject'}")
        String buttonReauthenticate = initLogin.body().htmlPath().getString("**.find { button -> button.@formaction == '/login/reauthenticate'}")

        assertThat("Back button exists with correct form action", buttonBack, is(backButton))
        assertThat("Reauthenticate button exists with correct form action", buttonReauthenticate, is(reauthenticateButton))
        assertThat("Correct logo", initLogin.body().asString().contains(Utils.getFileAsString("src/test/resources/base64_client_B_logo")))

        where:
        uiLocale | backButton | reauthenticateButton
        "et"     | "Tagasi"   | "Autendi uuesti"
        "en"     | "Back"     | "Re-authenticate"
        "ru"     | "??????????"    | "?????????? ??????????"
    }
}