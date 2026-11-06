// What the Mac is playing, for the phone's media controls. macOS only answers apps Apple has
// entitled to MediaRemote, but /usr/bin/perl is one: AyPhone loads this library into it (see
// NowPlaying.swift) and reads one JSON line per change from its output. Exits when AyPhone
// closes its input (quits or crashes).
#import <Foundation/Foundation.h>
#include <dlfcn.h>
#include <stdio.h>
#include <unistd.h>

typedef void (*RegisterFn)(dispatch_queue_t);
typedef void (*IsPlayingFn)(dispatch_queue_t, void (^)(Boolean));
typedef void (*InfoFn)(dispatch_queue_t, void (^)(NSDictionary *));

static IsPlayingFn isPlaying;
static InfoFn nowPlayingInfo;

static NSString *text(id value) { return [value isKindOfClass:NSString.class] ? value : @""; }

static void report(void) {
    isPlaying(dispatch_get_main_queue(), ^(Boolean playing) {
        nowPlayingInfo(dispatch_get_main_queue(), ^(NSDictionary *info) {
            NSDictionary *line = @{
                @"playing": @(playing),
                @"title": text(info[@"kMRMediaRemoteNowPlayingInfoTitle"]),
                @"artist": text(info[@"kMRMediaRemoteNowPlayingInfoArtist"]),
            };
            NSData *json = [NSJSONSerialization dataWithJSONObject:line options:0 error:nil];
            if (!json) return;
            fwrite(json.bytes, 1, json.length, stdout);
            fputc('\n', stdout);
            fflush(stdout);
        });
    });
}

void ayphone_now_playing(void) {
    void *mr = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_NOW);
    RegisterFn registerForNotifications = (RegisterFn)dlsym(mr, "MRMediaRemoteRegisterForNowPlayingNotifications");
    isPlaying = (IsPlayingFn)dlsym(mr, "MRMediaRemoteGetNowPlayingApplicationIsPlaying");
    nowPlayingInfo = (InfoFn)dlsym(mr, "MRMediaRemoteGetNowPlayingInfo");
    if (!registerForNotifications || !isPlaying || !nowPlayingInfo) return;

    registerForNotifications(dispatch_get_main_queue());
    for (NSString *name in @[@"kMRMediaRemoteNowPlayingApplicationIsPlayingDidChangeNotification",
                             @"kMRMediaRemoteNowPlayingInfoDidChangeNotification",
                             @"kMRMediaRemoteNowPlayingApplicationDidChangeNotification"]) {
        [NSNotificationCenter.defaultCenter addObserverForName:name object:nil queue:NSOperationQueue.mainQueue
                                                    usingBlock:^(NSNotification *note) { report(); }];
    }
    report();

    // AyPhone holds our input open; when it goes away, so do we.
    dispatch_source_t input = dispatch_source_create(DISPATCH_SOURCE_TYPE_READ, STDIN_FILENO, 0, dispatch_get_main_queue());
    dispatch_source_set_event_handler(input, ^{
        char buffer[64];
        if (read(STDIN_FILENO, buffer, sizeof buffer) <= 0) exit(0);
    });
    dispatch_resume(input);
    CFRunLoopRun();
}
