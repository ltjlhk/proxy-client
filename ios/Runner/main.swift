import UIKit
import Flutter

let argv = UnsafeMutablePointer(mutating: CommandLine.unsafeArgv)
let argc = CommandLine.argc
UIApplicationMain(argc, argv, nil, NSStringFromClass(AppDelegate.self))
