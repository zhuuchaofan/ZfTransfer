import SwiftUI

@main
struct ZfTransferApp: App {
    @StateObject private var camera = CameraLink()
    @StateObject private var imports = ImportStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(camera)
                .environmentObject(imports)
                .onAppear { camera.start() }
        }
    }
}
