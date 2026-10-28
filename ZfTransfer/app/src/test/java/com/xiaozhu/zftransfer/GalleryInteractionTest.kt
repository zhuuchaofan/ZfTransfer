package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class GalleryInteractionTest {
    @Test fun historicalAlbumPermissionDoesNotCreateImportEvidence() {
        assertTrue(ImportEvidence.includeLocal(false, true))
        assertFalse(ImportEvidence.confirmsLocal(false, false))
        assertFalse(ImportEvidence.includeLocal(false, false))
    }
    @Test fun ownFilesAndExistingRecordsRemainTrustedWithoutBroadPermission() {
        assertTrue(ImportEvidence.includeLocal(true, false))
        assertTrue(ImportEvidence.confirmsLocal(true, false))
        assertTrue(ImportEvidence.confirmsLocal(false, true))
    }
    private fun queuedPhoto(handle: Long) = io.github.noisetta.nikonpull.ptp.CameraObject(handle, 1, 0x3801, 100, 10, "photo$handle.jpg", "20261028T120000")
    @Test fun zoomAnimationMidpointPreservesTappedImagePoint() {
        val state = PhotoZoomState().apply { fit(1000f,800f,4000f,2000f) }
        val start = state.snapshot(); state.doubleTap(250f,400f); val target = state.snapshot()
        state.restore(start.towards(target,0.5f))
        assertEquals(1.5f,state.zoom,0.001f)
        assertEquals(250f,state.x + 1000f * state.baseScale * state.zoom,0.001f)
        assertEquals(400f,state.y + 1000f * state.baseScale * state.zoom,0.001f)
        state.restore(target); assertEquals(2f,state.zoom,0.001f)
    }
    @Test fun interruptedAnimationCanContinueFromItsCurrentTransform() {
        val state = PhotoZoomState().apply { fit(1000f,800f,4000f,2000f) }
        val start = state.snapshot(); state.doubleTap(250f,400f); val target = state.snapshot()
        state.restore(start.towards(target,0.4f)); val interrupted = state.snapshot()
        state.pan(20f,0f); assertEquals(interrupted.x + 20f,state.x,0.001f)
        state.restore(PhotoTransform(Float.NaN,0f,0f)); assertEquals(1.4f,state.zoom,0.001f)
    }
    @Test fun repeatedClicksDoNotRestartNetworkSettlingDeadline() {
        val state = ConnectionPreparation()
        assertEquals(1500L,state.waitMillis(1,"192.168.1.1",100))
        assertEquals(500L,state.waitMillis(1,"192.168.1.1",1100))
        assertEquals(0L,state.waitMillis(1,"192.168.1.1",1600))
    }
    @Test fun changingNetworkOrAddressRequiresANewSettlingWindow() {
        val state = ConnectionPreparation()
        state.waitMillis(1,"192.168.1.1",0)
        assertEquals(1500L,state.waitMillis(2,"192.168.1.1",2000))
        assertEquals(1500L,state.waitMillis(2,"10.0.0.1",4000))
        state.resetNetwork(); assertEquals(1500L,state.waitMillis(2,"10.0.0.1",6000))
    }
    @Test fun onlyFirstTwoTransportFailuresAreRetriedQuietly() {
        val state = ConnectionPreparation()
        val failure = IllegalStateException("connect", java.net.ConnectException("refused"))
        assertTrue(state.retryQuietly(failure)); assertTrue(state.retryQuietly(failure)); assertFalse(state.retryQuietly(failure))
        state.reset(); assertTrue(state.retryQuietly(failure))
    }
    @Test fun protocolRejectionIsNotSilencedAsNetworkTransition() {
        assertFalse(ConnectionPreparation().retryQuietly(IllegalStateException("Camera rejected session")))
    }
    @Test fun knownNonCameraSsidIsRejectedEvenWhenOfflineGatewayMatches() {
        assertNull(CameraNetworkSelector.select(listOf(CameraNetworkHint(1,"192.168.1.1","HOME",false,false)), "", "192.168.1.1"))
    }
    @Test fun mediumPreviewAcceptsPhoneSizedImagesAndRejectsThumbnailsOrOriginals() {
        assertTrue(MediumPreviewPolicy.usable(1620,1080,634805))
        assertTrue(MediumPreviewPolicy.usable(1080,1620,634805))
        assertTrue(MediumPreviewPolicy.usable(640,424,108912))
        assertFalse(MediumPreviewPolicy.usable(160,120,10685))
        assertFalse(MediumPreviewPolicy.usable(6048,4032,11000000))
        assertFalse(MediumPreviewPolicy.usable(-1,-1,0))
    }
    @Test fun normalHomeWifiIsNotProbedByConnectButton() {
        val home = CameraNetworkHint(1, "192.168.50.2", "HOME", true, false)
        assertNull(CameraNetworkSelector.select(listOf(home), "", "192.168.1.1"))
        assertNull(CameraNetworkSelector.select(listOf(home.copy(validated = false)), "", "192.168.1.1"))
    }
    @Test fun requestedCameraNetworkWinsOverOtherWifi() {
        val home = CameraNetworkHint(1, "192.168.50.2", "HOME", true, false)
        val camera = CameraNetworkHint(2, "192.168.1.1", null, false, true)
        assertEquals(camera, CameraNetworkSelector.select(listOf(home,camera), "", "192.168.1.1"))
    }
    @Test fun manuallyConnectedNikonWifiDoesNotNeedAddressConfiguration() {
        val camera = CameraNetworkHint(2, "192.168.1.1", "\"NIKON_Z_f_TEST\"", false, false)
        assertEquals(camera, CameraNetworkSelector.select(listOf(camera), "", "192.168.1.1"))
        assertEquals(camera.copy(ssid = "CUSTOM"), CameraNetworkSelector.select(listOf(camera.copy(ssid = "CUSTOM")), "", "10.0.0.1", setOf("CUSTOM")))
    }
    @Test fun hiddenSsidCanUsePreviouslyVerifiedCameraGateway() {
        val camera = CameraNetworkHint(2, "192.168.1.1", "<unknown ssid>", false, false)
        assertEquals(camera, CameraNetworkSelector.select(listOf(camera), "", "192.168.1.1"))
        assertNull(CameraNetworkSelector.select(listOf(camera.copy(validated = true)), "", "192.168.1.1"))
    }
    @Test fun explicitManualAddressRemainsAnAdvancedOverride() {
        val custom = CameraNetworkHint(3, "10.0.0.22", "LAN", true, false)
        assertEquals(custom, CameraNetworkSelector.select(listOf(custom), "10.0.0.22", "192.168.1.1"))
    }
    @Test fun explicitDisconnectPreventsReconnectEvenAfterLateCallbacks() {
        val state = ConnectionLifetime(); state.request(); state.failed(1000); state.stop()
        state.connected(); state.failed(1000000)
        assertFalse(state.desired); assertFalse(state.canReconnect(Long.MAX_VALUE))
        state.request(); assertTrue(state.canReconnect(0))
    }
    @Test fun screenChangesDoNotAlterConnectionIntent() {
        val state = ConnectionLifetime(); state.request(); state.connected()
        assertTrue(state.desired); assertTrue(state.canReconnect(1000))
    }
    @Test fun reconnectBackoffGrowsAndCapsAtOneMinute() {
        val state = ConnectionLifetime(); state.request()
        var now = 0L
        for (delay in listOf(5000L,10000L,20000L,40000L,60000L,60000L)) {
            state.failed(now)
            assertFalse(state.canReconnect(now + delay - 1)); assertTrue(state.canReconnect(now + delay))
            state.request() // Ensuring an existing service must not reset the backoff.
            assertFalse(state.canReconnect(now + delay - 1))
            now += delay
        }
    }
    @Test fun successfulReconnectResetsBackoff() {
        val state = ConnectionLifetime(); state.request(); repeat(5) { state.failed(0) }
        state.connected(); state.failed(1000)
        assertTrue(state.canReconnect(6000)); assertFalse(state.canReconnect(5999))
    }
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
        assertEquals("未找到相机服务", issue.title)
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
    @org.junit.Test fun filmstripPrefetchesNearEndOnly() {
        org.junit.Assert.assertFalse(FilmstripWindow.nearEnd(0, 400, 78, 40))
        org.junit.Assert.assertTrue(FilmstripWindow.nearEnd(2500, 400, 78, 40))
        org.junit.Assert.assertFalse(FilmstripWindow.nearEnd(2500, 400, 78, 80))
        org.junit.Assert.assertFalse(FilmstripWindow.nearEnd(2500, 0, 78, 40))
        org.junit.Assert.assertFalse(FilmstripWindow.nearEnd(2500, 400, 78, 0))
    }
    @org.junit.Test fun taskSnapshotControlsImportAndRetryConsistently() {
        val idle = TaskState()
        org.junit.Assert.assertTrue(idle.showRemaining(2))
        org.junit.Assert.assertFalse(idle.showRemaining(0))
        org.junit.Assert.assertEquals("再次导入", idle.previewImportLabel(1, true))
        val checking = TaskState(UserOperation.IMPORT)
        org.junit.Assert.assertFalse(checking.showRemaining(2))
        org.junit.Assert.assertEquals("正在处理…", checking.previewImportLabel(1, false))
        val downloading = checking.copy(transfer = ImportProgress(1, 42))
        org.junit.Assert.assertEquals("正在导入 42%", downloading.previewImportLabel(1, false))
        org.junit.Assert.assertEquals("其他照片导入 42%", downloading.previewImportLabel(2, false))
        org.junit.Assert.assertEquals(0, idle.percentage)
        org.junit.Assert.assertEquals(42, downloading.percentage)
    }
    @org.junit.Test fun pageAdmissionIsSharedAndRecoversAfterUserGesture() {
        val pages = PageReadGate()
        org.junit.Assert.assertTrue(pages.request("a", 40, 200, false, true, true))
        org.junit.Assert.assertFalse(pages.request("a", 40, 200, false, true, true))
        org.junit.Assert.assertFalse(pages.request("a", 80, 200, true, true, true))
        org.junit.Assert.assertTrue(pages.request("a", 80, 200, false, true, true))
        org.junit.Assert.assertTrue(pages.request("a", 80, 200, false, true, false))
        org.junit.Assert.assertTrue(pages.request("b", 80, 200, false, true, true))
        org.junit.Assert.assertFalse(pages.request("b", 200, 200, false, true, true))
        org.junit.Assert.assertFalse(pages.request("b", 120, 200, false, false, true))
        pages.resetGesture()
        org.junit.Assert.assertTrue(pages.request("b", 80, 200, false, true, true))
    }
    @org.junit.Test fun completionObserversDoNotConsumeEachOthersEvents() {
        val completions = ImportCompletions()
        completions.record(1)
        val firstObserver = completions.since(0)
        org.junit.Assert.assertEquals(firstObserver, completions.since(0))
        val cursor = firstObserver.last().sequence
        completions.record(2)
        org.junit.Assert.assertEquals(listOf(2L), completions.since(cursor).map { it.handle })
        org.junit.Assert.assertEquals(listOf(1L,2L), completions.since(0).map { it.handle })
        completions.beginBatch()
        completions.record(3)
        org.junit.Assert.assertEquals(listOf(3L), completions.since(cursor).map { it.handle })
        org.junit.Assert.assertTrue(completions.since(cursor).last().sequence > cursor)
    }
    @org.junit.Test fun filtersKeepCameraOrderAndOnlyTrustVerifiedHandles() {
        val a = io.github.noisetta.nikonpull.ptp.CameraObject(1,1,0x3801,100,0,"same.JPG","date")
        val b = a.copy(handle=2)
        val c = a.copy(handle=3)
        val all = listOf(a,b,c)
        org.junit.Assert.assertEquals(all,PhotoCatalog.visible(all,setOf(2),PhotoFilter.ALL))
        org.junit.Assert.assertEquals(listOf(b),PhotoCatalog.visible(all,setOf(2),PhotoFilter.IMPORTED))
        org.junit.Assert.assertEquals(listOf(a,c),PhotoCatalog.visible(all,setOf(2),PhotoFilter.NOT_IMPORTED))
    }
    @org.junit.Test fun removedImportEvidenceImmediatelyRemovesImportedMembership() {
        val a = io.github.noisetta.nikonpull.ptp.CameraObject(1,1,0x3801,100,0,"a.JPG","date")
        org.junit.Assert.assertEquals(listOf(a),PhotoCatalog.visible(listOf(a),setOf(1),PhotoFilter.IMPORTED))
        org.junit.Assert.assertTrue(PhotoCatalog.visible(listOf(a),emptySet(),PhotoFilter.IMPORTED).isEmpty())
        org.junit.Assert.assertEquals(listOf(a),PhotoCatalog.visible(listOf(a),emptySet(),PhotoFilter.NOT_IMPORTED))
    }
    @org.junit.Test fun previewDoesNotJumpWhenImportedPhotoLeavesActiveFilter() {
        val a = io.github.noisetta.nikonpull.ptp.CameraObject(1,1,0x3801,100,0,"a.JPG","date")
        val b = a.copy(handle=2)
        val c = a.copy(handle=3)
        val result = PhotoCatalog.appendPreview(listOf(a,b),listOf(a,b,c),setOf(1),PhotoFilter.NOT_IMPORTED)
        org.junit.Assert.assertEquals(listOf(a,b,c),result)
        org.junit.Assert.assertEquals(listOf(b,c),PhotoCatalog.visible(listOf(a,b,c),setOf(1),PhotoFilter.NOT_IMPORTED))
    }
    @org.junit.Test fun previewAppendDeduplicatesAndRespectsImportedFilter() {
        val a = io.github.noisetta.nikonpull.ptp.CameraObject(1,1,0x3801,100,0,"a.JPG","date")
        val b=a.copy(handle=2)
        val c=a.copy(handle=3)
        org.junit.Assert.assertEquals(listOf(a,c),PhotoCatalog.appendPreview(listOf(a),listOf(a,b,c),setOf(1,3),PhotoFilter.IMPORTED))
        org.junit.Assert.assertEquals(listOf(a,b,c),PhotoCatalog.appendPreview(listOf(a,b),listOf(a,b,c),emptySet(),PhotoFilter.ALL))
        org.junit.Assert.assertTrue(PhotoCatalog.visible(emptyList(),setOf(1),PhotoFilter.IMPORTED).isEmpty())
    }
}
