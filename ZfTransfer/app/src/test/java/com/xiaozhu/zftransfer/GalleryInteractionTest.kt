package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class GalleryInteractionTest {
    private fun queuedPhoto(handle: Long) = io.github.noisetta.nikonpull.ptp.CameraObject(handle, 1, 0x3801, 100, 10, "photo$handle.jpg", "20261028T120000")
    @Test fun filmstripCanReachBeginningMiddleAndEndOfAllLoadedPhotos() {
        assertEquals(0..5, FilmstripWindow.visible(0, 312, 78, 932))
        assertTrue(450 in FilmstripWindow.visible(450 * 78, 312, 78, 932))
        assertTrue(931 in FilmstripWindow.visible(932 * 78 - 312, 312, 78, 932))
        assertTrue(FilmstripWindow.visible(450 * 78, 312, 78, 932).count() <= 7)
    }
    @Test fun filmstripSelectionKeepsCurrentScrollWhenAlreadyVisible() {
        assertEquals(780, FilmstripWindow.reveal(780, 312, 78, 12, 40))
        assertEquals(0, FilmstripWindow.reveal(780, 312, 78, 0, 40))
        assertEquals(2808, FilmstripWindow.reveal(0, 312, 78, 39, 40))
    }
    @Test fun filmstripHandlesEmptyAndShortLists() {
        assertTrue(FilmstripWindow.visible(0, 312, 78, 0).isEmpty())
        assertEquals(0..1, FilmstripWindow.visible(0, 312, 78, 2))
        assertEquals(0, FilmstripWindow.reveal(0, 312, 78, 1, 2))
    }
    @Test fun pendingTaskRoundTripsWithCameraAndExactMetadata() {
        val obj = queuedPhoto(42).copy(filename = "照片.JPG")
        val queue = TransferQueue().apply { replace("Nikon|Zf|serial", listOf(obj)) }
        val restored = TransferQueueCodec.decode(TransferQueueCodec.encode(queue))
        assertEquals(queue.identity, restored.identity); assertEquals(queue.remaining, restored.remaining)
        assertTrue(restored.canRetry(queue.identity))
    }
    @Test fun truncatedTaskCannotBecomeAnEmptySuccessfulTask() {
        val queue = TransferQueue().apply { replace("camera", listOf(queuedPhoto(1))) }
        val encoded = TransferQueueCodec.encode(queue)
        assertTrue(runCatching { TransferQueueCodec.decode(encoded.dropLast(8)) }.isFailure)
        assertTrue(runCatching { TransferQueueCodec.decode("invalid") }.isFailure)
    }
    @Test fun retryRejectsAStaleTaskSnapshot() {
        val obj = queuedPhoto(1); val queue = TransferQueue().apply { replace("camera", listOf(obj)) }
        assertTrue(runCatching {
            TransferRunner(queue) {}.run("camera", listOf(queuedPhoto(2)), true, true, {}, { it }, { false }, { _, _ -> }, { _, _ -> })
        }.isFailure)
        assertEquals(listOf(obj), queue.remaining)
    }
    @Test fun failedBatchRetainsOnlyUnfinishedPhotosAndRetrySkipsVerifiedFiles() {
        val a = queuedPhoto(1); val b = queuedPhoto(2); val c = queuedPhoto(3)
        val queue = TransferQueue(); val saved = mutableSetOf<Long>(); var writes = 0
        val runner = TransferRunner(queue) { writes++ }
        try {
            runner.run("camera", listOf(a,b,c), false, false, {}, { it }, { it.handle in saved },
                { _, obj -> if (obj == b) error("connection lost") else saved.add(obj.handle) }, { _, _ -> })
            fail("must fail")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(b,c), queue.remaining)
        // Simulates gallery publication succeeding immediately before interruption.
        saved.add(b.handle)
        val result = runner.run("camera", queue.remaining, true, true, {}, { it }, { it.handle in saved },
            { _, obj -> saved.add(obj.handle) }, { _, _ -> })
        assertEquals(1 to 1, result); assertTrue(queue.remaining.isEmpty()); assertTrue(writes >= 4)
    }
    @Test fun retryRejectsDifferentCameraAndChangedObjectBeforeSaving() {
        val obj = queuedPhoto(1); val queue = TransferQueue().apply { replace("camera", listOf(obj)) }
        val runner = TransferRunner(queue) {}; var saves = 0
        for (identity in listOf("other", "camera")) {
            try {
                runner.run(identity, queue.remaining, true, true, {}, { it.copy(size = 200) }, { false },
                    { _, _ -> saves++ }, { _, _ -> }); fail("must reject")
            } catch (_: IllegalStateException) { }
        }
        assertEquals(0, saves); assertEquals(listOf(obj), queue.remaining)
    }
    @Test fun cancellationKeepsRemainingTaskAndDoesNotStartPhoto() {
        val obj = queuedPhoto(1); val queue = TransferQueue(); var saves = 0
        try {
            TransferRunner(queue) {}.run("camera", listOf(obj), false, false, { error("stopped") }, { it },
                { false }, { _, _ -> saves++ }, { _, _ -> }); fail("must cancel")
        } catch (_: IllegalStateException) { }
        assertEquals(0, saves); assertEquals(listOf(obj), queue.remaining)
    }
    @Test fun unidentifiedCameraCannotRetryAndQueueSnapshotsDoNotChange() {
        val a = queuedPhoto(1); val b = queuedPhoto(2)
        val queue = TransferQueue().apply { replace(null, listOf(a,b)) }
        val snapshot = queue.remaining; queue.completed(a)
        assertEquals(listOf(a,b), snapshot); assertEquals(listOf(b), queue.remaining)
        assertFalse(queue.canRetry(null))
        assertFalse(queue.matches(b.copy(captureDate = ""), b.copy(captureDate = "")))
    }
    @Test fun movingPinchFocusMovesPhotoWithoutChangingScale() {
        val state = PhotoZoomState().apply { fit(1000f, 800f, 4000f, 2000f); scale(2f, 500f, 400f) }
        state.scale(1f, 500f, 400f, 80f, 40f)
        assertEquals(2f, state.zoom, 0.001f)
        assertEquals(-420f, state.x, 0.001f)
        assertEquals(-60f, state.y, 0.001f)
    }
    @Test fun zoomStartsWithAspectFitWithoutCropping() {
        val state = PhotoZoomState().apply { fit(1000f, 800f, 4000f, 2000f) }
        assertEquals(0.25f, state.baseScale, 0.0001f)
        assertEquals(0f, state.x, 0.001f)
        assertEquals(150f, state.y, 0.001f)
    }
    @Test fun doubleTapKeepsTappedImagePointAnchoredAndThenResets() {
        val state = PhotoZoomState().apply { fit(1000f, 800f, 4000f, 2000f) }
        state.doubleTap(250f, 400f)
        assertEquals(2f, state.zoom, 0.001f)
        assertEquals(-250f, state.x, 0.001f)
        assertEquals(-100f, state.y, 0.001f)
        state.doubleTap(700f, 600f)
        assertEquals(1f, state.zoom, 0.001f)
        assertEquals(150f, state.y, 0.001f)
    }
    @Test fun pinchLimitsZoomAndReturnsToFitAtMinimum() {
        val state = PhotoZoomState().apply { fit(1000f, 800f, 4000f, 2000f) }
        state.scale(100f, 500f, 400f)
        assertEquals(5f, state.zoom, 0.001f)
        state.scale(0.001f, 500f, 400f)
        assertEquals(1f, state.zoom, 0.001f)
        assertEquals(0f, state.x, 0.001f)
        assertEquals(150f, state.y, 0.001f)
    }
    @Test fun portraitPanCentersShortAxisAndClampsLongAxis() {
        val state = PhotoZoomState().apply { fit(1000f, 800f, 1000f, 4000f); scale(2f, 500f, 400f) }
        state.pan(10000f, 10000f)
        assertEquals(300f, state.x, 0.001f)
        assertEquals(0f, state.y, 0.001f)
        state.pan(-10000f, -10000f)
        assertEquals(300f, state.x, 0.001f)
        assertEquals(-800f, state.y, 0.001f)
    }
    @Test fun switchingPhotoClearsPreviousZoomAndPan() {
        val state = PhotoZoomState().apply { fit(1000f, 800f, 4000f, 2000f); scale(3f, 400f, 300f); pan(100f, 20f) }
        state.fit(1000f, 800f, 1000f, 4000f)
        assertEquals(1f, state.zoom, 0.001f)
        assertEquals(400f, state.x, 0.001f)
        assertEquals(0f, state.y, 0.001f)
    }
    @Test fun invalidScaleDoesNotCorruptGeometry() {
        val state = PhotoZoomState().apply { fit(1000f, 800f, 4000f, 2000f) }
        state.scale(Float.NaN, 500f, 400f)
        state.scale(-2f, 500f, 400f)
        assertEquals(1f, state.zoom, 0.001f)
        assertEquals(150f, state.y, 0.001f)
    }
    @Test fun wrappedSocketTimeoutBecomesUsefulChineseMessage() {
        val issue = UserIssues.from(java.io.IOException("Connection failed", java.net.SocketTimeoutException("read timed out")), UserOperation.CONNECT)
        assertEquals("等待相机响应超时", issue.title)
        assertEquals(Recovery.WIFI_SETTINGS, issue.recovery)
        assertFalse(issue.message.contains("timed out"))
    }
    @Test fun connectionRefusedDoesNotPretendPasswordIsWrong() {
        val issue = UserIssues.from(java.net.ConnectException("ECONNREFUSED at 192.168.1.1:15740"), UserOperation.CONNECT)
        assertEquals("相机尚未准备好", issue.title)
        assertFalse(issue.message.contains("192.168"))
        assertFalse(issue.message.contains("密码错误"))
    }
    @Test fun storageFailureOffersStorageRecovery() {
        val issue = UserIssues.from(java.io.IOException("write failed: ENOSPC"), UserOperation.IMPORT)
        assertEquals("手机存储空间不足", issue.title)
        assertEquals(Recovery.STORAGE_SETTINGS, issue.recovery)
    }
    @Test fun permissionFailureOffersAppSettings() {
        assertEquals(Recovery.APP_SETTINGS, UserIssues.from(SecurityException("private provider details"), UserOperation.IMPORT).recovery)
    }
    @Test fun incompletePhotoDoesNotClaimSuccessfulImport() {
        val issue = UserIssues.from(IllegalStateException("文件大小不一致：1 / 100"), UserOperation.IMPORT)
        assertEquals("照片没有完整传输", issue.title)
        assertTrue(issue.message.contains("不会标记为已导入"))
        assertEquals(issue.title, UserIssues.from(java.io.IOException("Camera transfer ended early (1 of 100 bytes)"), UserOperation.IMPORT).title)
    }
    @Test fun unknownErrorsNeverExposePathsPasswordsOrPlatformExceptions() {
        val raw = "ErrnoException /private/user/photo.jpg password=not-for-display"
        val issue = UserIssues.from(java.io.IOException(raw), UserOperation.PREVIEW)
        assertEquals("暂时无法加载高清预览", issue.title)
        listOf("ErrnoException", "/private/", "not-for-display", "password=").forEach { assertFalse((issue.title + issue.message).contains(it)) }
    }
    private fun withPreviewCache(test: (java.io.File, OriginalPreviewCache) -> Unit) {
        val base = java.nio.file.Files.createDirectories(java.io.File("build/preview-tests").toPath().toAbsolutePath())
        val folder = java.nio.file.Files.createTempDirectory(base, "zf-preview-test-").toFile()
        try { test(folder, OriginalPreviewCache(folder, 12)) }
        finally { folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
    }
    @Test fun incompletePreviewIsNotPublishedAndTemporaryFileIsRemoved() = withPreviewCache { folder, cache ->
        assertTrue(runCatching { cache.store("camera/photo", 8) { it.write(byteArrayOf(1, 2)) } }.isFailure)
        assertNull(cache.find("camera/photo", 8))
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }
    @Test fun previewCacheVerifiesSizeAndStaysInsidePrivateDirectory() = withPreviewCache { folder, cache ->
        val file = cache.store("../../photo", 8) { it.write(ByteArray(8)) }
        assertEquals(folder.canonicalFile, file.canonicalFile.parentFile)
        assertEquals(file, cache.find("../../photo", 8))
        assertNull(cache.find("../../photo", 9))
        file.delete()
        assertNull(cache.find("../../photo", 8))
    }
    @Test fun previewCacheEvictsOlderOriginalsToKeepBudget() = withPreviewCache { _, cache ->
        cache.store("first", 8) { it.write(ByteArray(8)) }
        cache.store("second", 8) { it.write(ByteArray(8)) }
        assertNull(cache.find("first", 8))
        assertNotNull(cache.find("second", 8))
        assertTrue(runCatching { cache.store("too-large", 13) { it.write(ByteArray(13)) } }.isFailure)
    }
    @Test fun automaticHandshakeRetriesAreBoundedAndRespectStop() {
        val gate = AutoConnectGate()
        assertTrue(gate.take(1, "192.168.1.1"))
        repeat(2) {
            assertTrue(gate.retryAfterFailure(1, "192.168.1.1"))
            assertTrue(gate.take(1, "192.168.1.1"))
        }
        assertFalse(gate.retryAfterFailure(1, "192.168.1.1"))
        gate.stop(2)
        assertFalse(gate.retryAfterFailure(2, "192.168.1.1"))
    }
    @Test fun wifiProfileStringDoesNotExposePassword() {
        assertFalse(WifiProfile("sample", "test-only-password").toString().contains("test-only-password"))
    }
    @Test fun autoConnectAttemptsOncePerNetworkAndAddress() {
        val gate = AutoConnectGate()
        assertTrue(gate.take(1, "192.168.1.1"))
        assertFalse(gate.take(1, "192.168.1.1"))
        assertTrue(gate.take(2, "192.168.1.1"))
    }
    @Test fun explicitStopPreventsImmediateAutomaticReconnect() {
        val gate = AutoConnectGate()
        gate.stop(1)
        assertFalse(gate.take(1, "192.168.1.1"))
        gate.lost(1)
        assertTrue(gate.take(1, "192.168.1.1"))
    }
    @Test fun ordinaryInternetWifiIsNotTreatedAsCamera() {
        assertFalse(AutoConnectGate.isCameraCandidate(true, null, "192.168.1.1", "192.168.1.1", false))
        assertFalse(AutoConnectGate.isCameraCandidate(false, null, "10.0.0.1", "192.168.1.1", false))
        assertTrue(AutoConnectGate.isCameraCandidate(false, null, "192.168.1.1", "192.168.1.1", false))
        assertTrue(AutoConnectGate.isCameraCandidate(true, "\"NIKON_Z_f_sample\"", "10.0.0.1", "192.168.1.1", false))
        assertTrue(AutoConnectGate.isCameraCandidate(false, null, "10.0.0.1", "192.168.1.1", true))
    }
    @Test fun wifiProfileRejectsInvalidCredentialsWithoutTrimmingPassword() {
        assertTrue(WifiProfile("NIKON_example", "test-only-password").valid())
        assertFalse(WifiProfile("", "test-only-password").valid())
        assertFalse(WifiProfile("x".repeat(33), "test-only-password").valid())
        assertFalse(WifiProfile("x", "short").valid())
        assertFalse(WifiProfile("x", "密码不在日志中").valid())
        assertTrue(WifiProfile("x", " 123456 ").valid())
    }
    @Test fun cameraAddressRejectsWildcardAndNonUnicastEndpoints() {
        listOf("0.0.0.0", "0.1.2.3", "127.0.0.1", "224.0.0.1", "255.255.255.255", "192.168.1.256", "1.2.3", "1.2.x.4").forEach {
            assertFalse(it, CameraAddress.isUsable(it))
        }
    }
    @Test fun cameraAddressAcceptsRealLanGateways() {
        assertTrue(CameraAddress.isUsable("192.168.1.1"))
        assertTrue(CameraAddress.isUsable("10.0.0.1"))
        assertTrue(CameraAddress.isUsable("172.16.1.1"))
    }
    @Test fun dragSelectsContiguousRangeAndPreservesOtherSelections() {
        val gesture = RangeSelection(setOf(9), 2)
        assertEquals(setOf(2, 3, 4, 5, 9), gesture.at(5))
    }
    @Test fun reversingDragRestoresOriginalStateOutsideCurrentRange() {
        val gesture = RangeSelection(setOf(9), 2)
        gesture.at(7)
        assertEquals(setOf(2, 3, 9), gesture.at(3))
        assertEquals(setOf(0, 1, 2, 9), gesture.at(0))
    }
    @Test fun draggingFromSelectedItemDeselectsRangeReversibly() {
        val gesture = RangeSelection(setOf(1, 2, 3, 4, 9), 2)
        assertEquals(setOf(1, 9), gesture.at(4))
        assertEquals(setOf(1, 3, 4, 9), gesture.at(2))
    }
    @Test fun onlyPublishedMatchingJpegCanConfirmImport() {
        assertTrue(ImportEvidence.matches(100, 100, 0, "image/jpeg"))
        assertFalse(ImportEvidence.matches(100, 99, 0, "image/jpeg"))
        assertFalse(ImportEvidence.matches(100, 100, 1, "image/jpeg"))
        assertFalse(ImportEvidence.matches(0, 0, 0, "image/jpeg"))
        assertFalse(ImportEvidence.matches(100, 100, 0, null))
        assertFalse(ImportEvidence.matches(100, 100, 0, "image/png"))
    }
}
