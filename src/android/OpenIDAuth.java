package edu.berkeley.eecs.emission.cordova.jwtauth;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import net.openid.appauth.*;

import com.auth0.android.jwt.*;

import edu.berkeley.eecs.emission.cordova.connectionsettings.ConnectionSettings;
import edu.berkeley.eecs.emission.cordova.unifiedlogger.Log;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;

import org.apache.cordova.CordovaPlugin;

/**
 * Created by shankari on 8/21/17.
 *
 * Implementation of the dummy dev auth code to allow developers to login with multiple user IDs
 * for testing + to provide another exemplar of logging in properly :)
 */

class OpenIDAuth implements AuthTokenCreator {
    private CordovaPlugin mPlugin;
    private AuthPendingResult mAuthPending;
    private AuthorizationService mAuthService;
    private AuthStateManager mStateManager;
    private Context mCtxt;

    private static final String TAG = "OpenIDAuth";
    private static final int RC_AUTH = 100;

    // This has to be a class instance instead of a singleton like in
    // iOS because we are not supposed to store contexts in static variables
    // singleton pattern has static GoogleAccountManagerAuth -> mCtxt
    OpenIDAuth(Context ctxt) {
        mCtxt = ctxt;
        mAuthService = new AuthorizationService(mCtxt);
        mStateManager = AuthStateManager.getInstance(mCtxt);
    }

    @Override
    public AuthPendingResult uiSignIn(CordovaPlugin plugin) {
        this.mAuthPending = new AuthPendingResult();
        this.mPlugin = plugin;

        Uri issuerUri = Uri.parse(ConnectionSettings.getAuthValue(mCtxt, "discoveryURI"));
        AuthorizationServiceConfiguration.fetchFromIssuer(
                        issuerUri,
                        new AuthorizationServiceConfiguration.RetrieveConfigurationCallback() {
                            public void onFetchConfigurationCompleted(
                                    @Nullable AuthorizationServiceConfiguration serviceConfiguration,
                                    @Nullable AuthorizationException ex) {
                                if (ex != null) {
                                    Log.e(mCtxt, TAG, "failed to fetch configuration");
                                    return;
                                }

                                // use serviceConfiguration as needed
                                // service configuration retrieved, proceed to authorization...
                                AuthorizationRequest.Builder authRequestBuilder = new AuthorizationRequest.Builder(
                                        serviceConfiguration,
                                        ConnectionSettings.getAuthValue(mCtxt, "clientID"),
                                        ResponseTypeValues.CODE,
                                        Uri.parse("emission.auth://oauth2redirect"))
                                        .setScope(ConnectionSettings.getAuthValue(mCtxt, "scope"));
                                AuthorizationRequest authRequest = authRequestBuilder.build();

                                // AuthorizationService authService = new AuthorizationService(mCtxt);
                                Intent authIntent = mAuthService.getAuthorizationRequestIntent(authRequest);
                                mPlugin.cordova.setActivityResultCallback(mPlugin);
                                mPlugin.cordova.getActivity().startActivityForResult(authIntent, RC_AUTH);
                            }
                        });

        return mAuthPending;
    }

    @Override
    public AuthPendingResult getUserEmail() {
        return readStoredUserEmail(mCtxt);
    }

    @Override
    public AuthPendingResult getServerToken() {
        AuthPendingResult authPending = new AuthPendingResult();

        String userName = UserProfile.getInstance(mCtxt).getUserEmail();
        String serverToken = mStateManager.getCurrent().getIdToken();

        AuthResult result = new AuthResult(
                new Status(CommonStatusCodes.SUCCESS),
                userName,
                serverToken);
        authPending.setResult(result);

        return authPending;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i(mCtxt, TAG, "onActivityResult get called");
        if (requestCode == RC_AUTH) {
            AuthorizationResponse response = AuthorizationResponse.fromIntent(data);
            AuthorizationException ex = AuthorizationException.fromIntent(data);
            if (response == null) {
                // authorization failed, check ex for more details
                Log.i(mCtxt, TAG, "Exception occurred: " + ex.toJsonString());
                return;
            }
            // authorization completed
            if (response != null && response.authorizationCode != null) {
                // authorization code exchange is required
                mStateManager.updateAfterAuthorization(response, ex);
                exchangeAuthorizationCode(response);
            } else if (ex != null) {
                Log.d(mCtxt, TAG, "Authorization flow failed: " + ex.getMessage());
            } else {
                Log.d(mCtxt, TAG, "No authorization state retained - reauthorization required");
            }
        } else {
            Log.d(mCtxt, TAG, "onActivityResult(" + requestCode + "," + resultCode + "," + data.getDataString());
            Log.i(mCtxt, TAG, "unknown intent, ignoring call...");
        }
    }

    private void exchangeAuthorizationCode(AuthorizationResponse authorizationResponse) {
        performTokenRequest(
                authorizationResponse.createTokenExchangeRequest(),
                new AuthorizationService.TokenResponseCallback() {
                    public void onTokenRequestCompleted(@Nullable TokenResponse tokenResponse,
                                                        @Nullable AuthorizationException authException) {

                        mStateManager.updateAfterTokenResponse(tokenResponse, authException);

                        AuthState authState = mStateManager.getCurrent();
                        if (!authState.isAuthorized()) {
                            final String message = "Authorization Code exchange failed"
                                    + ((authException != null) ? authException.error : "");

                            Log.d(mCtxt, TAG, message);
                        } else {
                            String idToken = authState.getIdToken();
                            Log.i(mCtxt, TAG, "id token retrieved: " + idToken);

                            String userEmail = getJWTEmail(idToken);
                            UserProfile.getInstance(mCtxt).setUserEmail(userEmail);
                            AuthResult authResult = new AuthResult(
                                    new Status(CommonStatusCodes.SUCCESS),
                                    userEmail,
                                    userEmail);
                            mAuthPending.setResult(authResult);
                        }
                    }
                });
    }

    private void performTokenRequest(
            TokenRequest request,
            AuthorizationService.TokenResponseCallback callback) {
        ClientAuthentication clientAuthentication;
        try {
            clientAuthentication = mStateManager.getCurrent().getClientAuthentication();
        } catch (ClientAuthentication.UnsupportedAuthenticationMethod ex) {
            Log.d(mCtxt, TAG, "Token request cannot be made, client authentication for the token "
                    + "endpoint could not be constructed");
            return;
        }

        mAuthService.performTokenRequest(
                request,
                clientAuthentication,
                callback);
    }

    private String getJWTEmail(String token) {
        JWT parsedJWT = new JWT(token);
        Claim email = parsedJWT.getClaim("email");
        return email.asString();
    }

    @Override
    public void onNewIntent(Intent intent) {
        Log.d(mCtxt, TAG, "in openid auth code, onIntent("+intent.getDataString()+" called, ignoring");
    }

    private AuthPendingResult readStoredUserEmail(Context ctxt) {
        AuthPendingResult authPending = new AuthPendingResult();
        String userEmail = UserProfile.getInstance(ctxt).getUserEmail();
        AuthResult result = new AuthResult(
                new Status(CommonStatusCodes.SUCCESS),
                userEmail, userEmail);
        authPending.setResult(result);
        return authPending;
    }
}
