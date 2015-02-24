#import <Foundation/Foundation.h>

@class JSContext;

/**
 This class wraps a `JSContext` and listens a TCP server, accepting 
 ClojureScript-REPL JavaScript expressions to evaluate, evaluating
 them in JSC, and returning the results.
 */
@interface ABYServer : NSObject<NSStreamDelegate>

/**
 Initializes this server.
 
 @param context the supplied context
 @param compilerOutputDirectory the compiler output directory
 */
-(id)initWithContext:(JSContext*)context compilerOutputDirectory:(NSURL*)compilerOutputDirectory;

/**
 Starts server listening and wrapping a context.
 @param port the port to listen on
 @param jsContext the context to wrap
 @return YES iff successful
 */
-(BOOL)startListening:(unsigned short)port;

@end
