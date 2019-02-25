#import "OtaUpdatePlugin.h"

@implementation OtaUpdatePlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  FlutterMethodChannel* channel = [FlutterMethodChannel
      methodChannelWithName:@"ota_update"
            binaryMessenger:[registrar messenger]];
  OtaUpdatePlugin* instance = [[OtaUpdatePlugin alloc] init];
  [registrar addMethodCallDelegate:instance channel:channel];
}

- (void)handleMethodCall:(FlutterMethodCall*)call result:(FlutterResult)result {
  if ([@"execute" isEqualToString:call.method]) {
    NSDictionary *arguments = [call arguments];
    NSString *utlString = arguments[@"url"];
    NSString* webStringURL = [utlString stringByAddingPercentEscapesUsingEncoding:NSUTF8StringEncoding];
    NSURL* url = [NSURL URLWithString:webStringURL];
    [[UIApplication sharedApplication] openURL:url];
    result(@YES);
  } else {
    result(FlutterMethodNotImplemented);
  }
}

@end
