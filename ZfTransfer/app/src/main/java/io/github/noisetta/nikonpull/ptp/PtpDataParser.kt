package io.github.noisetta.nikonpull.ptp

internal object PtpDataParser {
    fun parseDeviceInfo(data: ByteArray, responderGuid: String = ""): CameraDeviceInfo {
        val reader = LittleEndianReader(data)
        val standardVersion = reader.u16()
        val vendorExtensionId = reader.u32()
        val vendorExtensionVersion = reader.u16()
        val vendorExtensionDescription = reader.ptpString()
        reader.u16() // FunctionalMode
        val operations = reader.u16Array()
        reader.u16Array() // EventsSupported
        reader.u16Array() // DevicePropertiesSupported
        reader.u16Array() // CaptureFormats
        val imageFormats = reader.u16Array()
        val manufacturer = reader.ptpString()
        val model = reader.ptpString()
        val deviceVersion = reader.ptpString()
        val serialNumber = reader.ptpString()
        return CameraDeviceInfo(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            vendorExtensionVersion = vendorExtensionVersion,
            vendorExtensionDescription = vendorExtensionDescription,
            operationsSupported = operations.toSet(),
            imageFormatsSupported = imageFormats.toSet(),
            manufacturer = manufacturer,
            model = model,
            deviceVersion = deviceVersion,
            serialNumber = serialNumber,
            responderGuid = responderGuid,
        )
    }

    /** One property of one object, from a GetObjectPropList response. */
    data class ObjectPropValue(val handle: Long, val property: Int, val number: Long, val text: String?)

    /** MTP ObjectPropList: a count, then handle, property code, data type and a value per element. */
    fun parseObjectPropList(data: ByteArray): List<ObjectPropValue> {
        val reader = LittleEndianReader(data)
        val count = reader.u32()
        if (count > MAX_ARRAY_ITEMS || count * MIN_PROP_ELEMENT_BYTES > reader.remaining()) {
            throw PtpException("Camera returned an invalid PTP property list")
        }
        return List(count.toInt()) {
            val handle = reader.u32()
            val property = reader.u16()
            when (val type = reader.u16()) {
                TYPE_INT8, TYPE_UINT8 -> ObjectPropValue(handle, property, reader.u8().toLong(), null)
                TYPE_INT16, TYPE_UINT16 -> ObjectPropValue(handle, property, reader.u16().toLong(), null)
                TYPE_INT32, TYPE_UINT32 -> ObjectPropValue(handle, property, reader.u32(), null)
                TYPE_INT64, TYPE_UINT64 -> ObjectPropValue(handle, property, reader.u64(), null)
                TYPE_STRING -> ObjectPropValue(handle, property, 0, reader.ptpString())
                else -> throw PtpException("Camera returned a property of unsupported type 0x${type.toString(16)}")
            }
        }
    }

    /** PTP/IP event payload: event code, transaction id, then up to three parameters. */
    fun parseEvent(data: ByteArray): CameraEvent {
        val reader = LittleEndianReader(data)
        val code = reader.u16()
        reader.u32() // TransactionID
        val params = ArrayList<Long>(3)
        while (params.size < 3 && reader.remaining() >= 4) params.add(reader.u32())
        return CameraEvent(code, params)
    }

    fun parseU32Array(data: ByteArray): List<Long> {
        val reader = LittleEndianReader(data)
        val count = reader.u32()
        if (count > MAX_ARRAY_ITEMS || count * 4L > reader.remaining()) {
            throw PtpException("Camera returned an invalid PTP array")
        }
        return List(count.toInt()) { reader.u32() }
    }

    fun parseObjectInfo(handle: Long, data: ByteArray): CameraObject {
        val reader = LittleEndianReader(data)
        val storageId = reader.u32()
        val format = reader.u16()
        reader.u16() // ProtectionStatus
        val objectSize = reader.u32()
        reader.u16() // ThumbFormat
        val thumbnailSize = reader.u32()
        reader.u32() // ThumbPixWidth
        reader.u32() // ThumbPixHeight
        reader.u32() // ImagePixWidth
        reader.u32() // ImagePixHeight
        reader.u32() // ImageBitDepth
        reader.u32() // ParentObject
        reader.u16() // AssociationType
        reader.u32() // AssociationDesc
        reader.u32() // SequenceNumber
        val filename = reader.ptpString()
        val captureDate = if (reader.remaining() > 0) reader.ptpString() else ""

        return CameraObject(
            handle = handle,
            storageId = storageId,
            format = format,
            size = objectSize,
            thumbnailSize = thumbnailSize,
            filename = filename.ifBlank { "DSC_${handle.toString(16)}.JPG" },
            captureDate = captureDate,
        )
    }

    private const val MAX_ARRAY_ITEMS = 100_000L
    private const val MIN_PROP_ELEMENT_BYTES = 9L // handle + property + type + at least one value byte
    private const val TYPE_INT8 = 0x0001
    private const val TYPE_UINT8 = 0x0002
    private const val TYPE_INT16 = 0x0003
    private const val TYPE_UINT16 = 0x0004
    private const val TYPE_INT32 = 0x0005
    private const val TYPE_UINT32 = 0x0006
    private const val TYPE_INT64 = 0x0007
    private const val TYPE_UINT64 = 0x0008
    private const val TYPE_STRING = 0xFFFF

    private fun LittleEndianReader.u16Array(): List<Int> {
        val count = u32()
        if (count > MAX_ARRAY_ITEMS || count * 2L > remaining()) {
            throw PtpException("Camera returned an invalid PTP array")
        }
        return List(count.toInt()) { u16() }
    }
}
