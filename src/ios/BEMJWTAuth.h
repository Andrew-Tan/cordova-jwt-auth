#import <Cordova/CDV.h>
#import <GoogleSignIn/GoogleSignIn.h> 

@interface BEMJWTAuth: CDVPlugin <GIDSignInDelegate, GIDSignInUIDelegate>

- (void) getUserEmail:(CDVInvokedUrlCommand*)command;
- (void) signIn:(CDVInvokedUrlCommand*)command;
- (void) getJWT:(CDVInvokedUrlCommand*)command;

@end
