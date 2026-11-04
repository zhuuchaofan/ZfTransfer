import ZfCore

let slot1: Int64 = 65537
let slot2: Int64 = 131073

func obj(_ handle: Int64, _ name: String, _ date: String, storage: Int64 = 1, format: Int = 0x3801, size: Int64 = 100) -> CameraObject {
    CameraObject(handle: handle, storageId: storage, format: format, size: size, filename: name, captureDate: date)
}
