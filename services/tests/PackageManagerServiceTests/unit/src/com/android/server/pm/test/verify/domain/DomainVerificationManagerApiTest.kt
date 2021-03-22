/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm.test.verify.domain

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.parsing.component.ParsedActivity
import android.content.pm.parsing.component.ParsedIntentInfo
import android.content.pm.verify.domain.DomainVerificationInfo
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import android.os.PatternMatcher
import android.os.Process
import android.util.ArraySet
import com.android.server.pm.PackageSetting
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.pm.verify.domain.DomainVerificationService
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import java.util.UUID
import kotlin.test.assertFailsWith

class DomainVerificationManagerApiTest {

    companion object {
        private const val PKG_ONE = "com.test.one"
        private const val PKG_TWO = "com.test.two"
        private const val PKG_THREE = "com.test.three"

        private val UUID_ONE = UUID.fromString("1b041c96-8d37-4932-a858-561bfac5947c")
        private val UUID_TWO = UUID.fromString("a3389c16-7f9f-4e86-85e3-500d1249c74c")
        private val UUID_THREE = UUID.fromString("0b3260ed-07c4-4b45-840b-237f8fb8b433")
        private val UUID_INVALID = UUID.fromString("ad33babc-490b-4965-9d78-7e91248b00f")

        private val DOMAIN_BASE = DomainVerificationManagerApiTest::class.java.packageName
        private val DOMAIN_1 = "one.$DOMAIN_BASE"
        private val DOMAIN_2 = "two.$DOMAIN_BASE"
        private val DOMAIN_3 = "three.$DOMAIN_BASE"
        private val DOMAIN_4 = "four.$DOMAIN_BASE"
    }

    @Test
    fun queryValidVerificationPackageNames() {
        val pkgWithDomains = mockPkgSetting(PKG_ONE, UUID_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkgWithoutDomains = mockPkgSetting(PKG_TWO, UUID_TWO, emptyList())

        val service = makeService(pkgWithDomains, pkgWithoutDomains).apply {
            addPackages(pkgWithDomains, pkgWithoutDomains)
        }

        assertThat(service.queryValidVerificationPackageNames())
                .containsExactly(pkgWithDomains.getName())
    }

    @Test
    fun getDomainVerificationInfoId() {
        val pkgWithDomains = mockPkgSetting(PKG_ONE, UUID_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkgWithoutDomains = mockPkgSetting(PKG_TWO, UUID_TWO, emptyList())

        val service = makeService(pkgWithDomains, pkgWithoutDomains).apply {
            addPackages(pkgWithDomains, pkgWithoutDomains)
        }

        assertThat(service.getDomainVerificationInfoId(PKG_ONE)).isEqualTo(UUID_ONE)
        assertThat(service.getDomainVerificationInfoId(PKG_TWO)).isEqualTo(UUID_TWO)

        assertThat(service.getDomainVerificationInfoId("invalid.pkg.name")).isEqualTo(null)
    }

    @Test
    fun getDomainVerificationInfo() {
        val pkgWithDomains = mockPkgSetting(PKG_ONE, UUID_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkgWithoutDomains = mockPkgSetting(PKG_TWO, UUID_TWO, emptyList())

        val service = makeService(pkgWithDomains, pkgWithoutDomains).apply {
            addPackages(pkgWithDomains, pkgWithoutDomains)
        }

        val infoOne = service.getDomainVerificationInfo(pkgWithDomains.getName())
        assertThat(infoOne).isNotNull()
        assertThat(infoOne!!.identifier).isEqualTo(pkgWithDomains.domainSetId)
        assertThat(infoOne.packageName).isEqualTo(pkgWithDomains.getName())
        assertThat(infoOne.hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DomainVerificationInfo.STATE_NO_RESPONSE,
                DOMAIN_2 to DomainVerificationInfo.STATE_NO_RESPONSE,
        ))

        assertThat(service.getDomainVerificationInfo(pkgWithoutDomains.getName())).isNull()

        assertFailsWith(PackageManager.NameNotFoundException::class) {
            service.getDomainVerificationInfo("invalid.pkg.name")
        }
    }

    @Test
    fun setStatus() {
        val pkg1 = mockPkgSetting(PKG_ONE, UUID_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkg2 = mockPkgSetting(PKG_TWO, UUID_TWO, listOf(DOMAIN_3, DOMAIN_4))

        val map = mutableMapOf(pkg1.getName() to pkg1, pkg2.getName() to pkg2)
        val service = makeService(map::get).apply { addPackages(pkg1, pkg2) }

        assertThat(service.setStatus(UUID_ONE, setOf(DOMAIN_2), 1100))
                .isEqualTo(DomainVerificationManager.STATUS_OK)

        assertThat(service.setStatus(UUID_INVALID, setOf(DOMAIN_1), 1100))
                .isEqualTo(DomainVerificationManager.ERROR_DOMAIN_SET_ID_INVALID)

        assertThat(DomainVerificationJavaUtil.setStatusForceNullable(service, null,
                setOf(DOMAIN_1), 1100))
                .isEqualTo(DomainVerificationManager.ERROR_DOMAIN_SET_ID_NULL)

        assertThat(DomainVerificationJavaUtil.setStatusForceNullable(service, UUID_ONE, null,
                1100)).isEqualTo(DomainVerificationManager.ERROR_DOMAIN_SET_NULL_OR_EMPTY)

        assertThat(service.setStatus(UUID_ONE, emptySet(), 1100))
                .isEqualTo(DomainVerificationManager.ERROR_DOMAIN_SET_NULL_OR_EMPTY)

        assertThat(service.setStatus(UUID_ONE, setOf(DOMAIN_3), 1100))
                .isEqualTo(DomainVerificationManager.ERROR_UNKNOWN_DOMAIN)

        assertThat(service.setStatus(UUID_ONE, setOf(DOMAIN_1, DOMAIN_2, DOMAIN_3), 1100))
                .isEqualTo(DomainVerificationManager.ERROR_UNKNOWN_DOMAIN)

        assertThat(service.setStatus(UUID_ONE, setOf(DOMAIN_1), 15))
                .isEqualTo(DomainVerificationManager.ERROR_INVALID_STATE_CODE)

        map.clear()
        assertFailsWith(PackageManager.NameNotFoundException::class){
            service.setStatus(UUID_ONE, setOf(DOMAIN_1), 1100)
        }
    }

    @Test
    fun setDomainVerificationLinkHandlingAllowed() {
        val pkg1 = mockPkgSetting(PKG_ONE, UUID_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkg2 = mockPkgSetting(PKG_TWO, UUID_TWO, listOf(DOMAIN_3, DOMAIN_4))

        val map = mutableMapOf(pkg1.getName() to pkg1, pkg2.getName() to pkg2)
        val service = makeService(map::get).apply { addPackages(pkg1, pkg2) }

        service.setDomainVerificationLinkHandlingAllowed(PKG_ONE, false, 0);

        // Should edit same package, same user
        assertThat(service.getDomainVerificationUserState(PKG_ONE, 0)
                ?.isLinkHandlingAllowed).isEqualTo(false)

        // Shouldn't edit different user
        assertThat(service.getDomainVerificationUserState(PKG_ONE, 1)
                ?.isLinkHandlingAllowed).isEqualTo(true)

        // Shouldn't edit different package
        assertThat(service.getDomainVerificationUserState(PKG_TWO, 0)
                ?.isLinkHandlingAllowed).isEqualTo(true)

        assertFailsWith(PackageManager.NameNotFoundException::class){
            service.setDomainVerificationLinkHandlingAllowed("invalid.pkg.name", false, 0);
        }
    }

    @Test
    fun setUserSelection() {
        val pkg1 = mockPkgSetting(PKG_ONE, UUID_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkg2 = mockPkgSetting(PKG_TWO, UUID_TWO, listOf(DOMAIN_3, DOMAIN_4))
        val pkg3 = mockPkgSetting(PKG_THREE, UUID_THREE, listOf(DOMAIN_1, DOMAIN_2))

        val map = mutableMapOf(
                pkg1.getName() to pkg1,
                pkg2.getName() to pkg2,
                pkg3.getName() to pkg3
        )
        val service = makeService(map::get).apply { addPackages(pkg1, pkg2, pkg3) }

        assertThat(service.setUserSelection(UUID_ONE, setOf(DOMAIN_2), true, 0))
                .isEqualTo(DomainVerificationManager.STATUS_OK)

        assertThat(service.setUserSelection(UUID_INVALID, setOf(DOMAIN_1), true, 0))
                .isEqualTo(DomainVerificationManager.ERROR_DOMAIN_SET_ID_INVALID)

        assertThat(DomainVerificationJavaUtil.setUserSelectionForceNullable(service, null,
                setOf(DOMAIN_1), true, 0))
                .isEqualTo(DomainVerificationManager.ERROR_DOMAIN_SET_ID_NULL)

        assertThat(DomainVerificationJavaUtil.setUserSelectionForceNullable(service, UUID_ONE, null,
                true, 0)).isEqualTo(DomainVerificationManager.ERROR_DOMAIN_SET_NULL_OR_EMPTY)

        assertThat(service.setUserSelection(UUID_ONE, emptySet(), true, 0))
                .isEqualTo(DomainVerificationManager.ERROR_DOMAIN_SET_NULL_OR_EMPTY)

        assertThat(service.setUserSelection(UUID_ONE, setOf(DOMAIN_3), true, 0))
                .isEqualTo(DomainVerificationManager.ERROR_UNKNOWN_DOMAIN)

        assertThat(service.setUserSelection(UUID_ONE, setOf(DOMAIN_1, DOMAIN_2, DOMAIN_3), true, 0))
                .isEqualTo(DomainVerificationManager.ERROR_UNKNOWN_DOMAIN)

        service.setStatus(UUID_ONE, setOf(DOMAIN_2), DomainVerificationInfo.STATE_SUCCESS)

        assertThat(service.setUserSelection(UUID_THREE, setOf(DOMAIN_2), true, 0))
            .isEqualTo(DomainVerificationManager.ERROR_UNABLE_TO_APPROVE)

        map.clear()
        assertFailsWith(PackageManager.NameNotFoundException::class){
            service.setUserSelection(UUID_ONE, setOf(DOMAIN_1), true, 0)
        }
    }

    @Test
    fun getDomainVerificationUserState() {
        val pkgWithDomains = mockPkgSetting(PKG_ONE, UUID_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkgWithoutDomains = mockPkgSetting(PKG_TWO, UUID_TWO, emptyList())

        val service = makeService(pkgWithDomains, pkgWithoutDomains).apply {
            addPackages(pkgWithDomains, pkgWithoutDomains)
        }

        val infoOne = service.getDomainVerificationUserState(pkgWithDomains.getName(), 0)
        assertThat(infoOne).isNotNull()
        assertThat(infoOne!!.identifier).isEqualTo(pkgWithDomains.domainSetId)
        assertThat(infoOne.packageName).isEqualTo(pkgWithDomains.getName())
        assertThat(infoOne.isLinkHandlingAllowed).isTrue()
        assertThat(infoOne.hostToStateMap).containsExactlyEntriesIn(mapOf(
                DOMAIN_1 to DomainVerificationUserState.DOMAIN_STATE_NONE,
                DOMAIN_2 to DomainVerificationUserState.DOMAIN_STATE_NONE,
        ))

        val infoTwo = service.getDomainVerificationUserState(pkgWithoutDomains.getName(), 0)
        assertThat(infoTwo).isNotNull()
        assertThat(infoTwo!!.identifier).isEqualTo(pkgWithoutDomains.domainSetId)
        assertThat(infoTwo.packageName).isEqualTo(pkgWithoutDomains.getName())
        assertThat(infoOne.isLinkHandlingAllowed).isTrue()
        assertThat(infoTwo.hostToStateMap).isEmpty()

        assertFailsWith(PackageManager.NameNotFoundException::class) {
            service.getDomainVerificationUserState("invalid.pkg.name", 0)
        }
    }

    @Test
    fun getOwnersForDomain() {
        val pkg1 = mockPkgSetting(PKG_ONE, UUID_ONE, listOf(DOMAIN_1, DOMAIN_2))
        val pkg2 = mockPkgSetting(PKG_TWO, UUID_TWO, listOf(DOMAIN_1, DOMAIN_2))

        val service = makeService(pkg1, pkg2).apply {
            addPackages(pkg1, pkg2)
        }

        assertThat(service.getOwnersForDomain(DOMAIN_1, 0)).isEmpty()

        service.setStatus(pkg1.domainSetId, setOf(DOMAIN_1), DomainVerificationInfo.STATE_SUCCESS)

        service.setStatus(pkg2.domainSetId, setOf(DOMAIN_1), DomainVerificationInfo.STATE_SUCCESS)

        service.setUserSelection(pkg1.domainSetId, setOf(DOMAIN_2), true, 0)

        service.getOwnersForDomain(DOMAIN_1, 0).let {
            assertThat(it).hasSize(2)
            assertThat(it[0].packageName).isEqualTo(pkg1.getName())
            assertThat(it[0].isOverrideable).isEqualTo(false)
            assertThat(it[1].packageName).isEqualTo(pkg2.getName())
            assertThat(it[1].isOverrideable).isEqualTo(false)
        }

        service.getOwnersForDomain(DOMAIN_2, 0).let {
            assertThat(it).hasSize(1)
            assertThat(it.single().packageName).isEqualTo(pkg1.getName())
            assertThat(it.single().isOverrideable).isEqualTo(true)
        }
        assertThat(service.getOwnersForDomain(DOMAIN_2, 1)).isEmpty()

        service.setUserSelection(pkg2.domainSetId, setOf(DOMAIN_2), true, 0)
        service.getOwnersForDomain(DOMAIN_2, 0).let {
            assertThat(it).hasSize(1)
            assertThat(it.single().packageName).isEqualTo(pkg2.getName())
            assertThat(it.single().isOverrideable).isEqualTo(true)
        }
        assertThat(service.getOwnersForDomain(DOMAIN_2, 1)).isEmpty()
    }

    private fun makeService(vararg pkgSettings: PackageSetting) =
            makeService { pkgName -> pkgSettings.find { pkgName == it.getName() } }

    private fun makeService(pkgSettingFunction: (String) -> PackageSetting? = { null }) =
            DomainVerificationService(mockThrowOnUnmocked {
                // Assume the test has every permission necessary
                whenever(enforcePermission(anyString(), anyInt(), anyInt(), anyString()))
                whenever(checkPermission(anyString(), anyInt(), anyInt())) {
                    PackageManager.PERMISSION_GRANTED
                }
            }, mockThrowOnUnmocked {
                whenever(linkedApps) { ArraySet<String>() }
            }, mockThrowOnUnmocked {
                whenever(isChangeEnabledInternalNoLogging(anyLong(), any())) { true }
            }).apply {
                setConnection(mockThrowOnUnmocked {
                    whenever(filterAppAccess(anyString(), anyInt(), anyInt())) { false }
                    whenever(doesUserExist(0)) { true }
                    whenever(doesUserExist(1)) { true }
                    whenever(scheduleWriteSettings())

                    // Need to provide an internal UID so some permission checks are ignored
                    whenever(callingUid) { Process.ROOT_UID }
                    whenever(callingUserId) { 0 }

                    whenever(getPackageSettingLocked(anyString())) {
                        pkgSettingFunction(arguments[0] as String)
                    }
                    whenever(getPackageLocked(anyString())) {
                        pkgSettingFunction(arguments[0] as String)?.getPkg()
                    }
                })
            }

    private fun mockPkgSetting(pkgName: String, domainSetId: UUID, domains: List<String> = listOf(
            DOMAIN_1, DOMAIN_2
    )) = mockThrowOnUnmocked<PackageSetting> {
        val pkg = mockThrowOnUnmocked<AndroidPackage> {
            whenever(packageName) { pkgName }
            whenever(targetSdkVersion) { Build.VERSION_CODES.S }

            val activityList = listOf(
                    ParsedActivity().apply {
                        domains.forEach {
                            addIntent(
                                    ParsedIntentInfo().apply {
                                        autoVerify = true
                                        addAction(Intent.ACTION_VIEW)
                                        addCategory(Intent.CATEGORY_BROWSABLE)
                                        addCategory(Intent.CATEGORY_DEFAULT)
                                        addDataScheme("http")
                                        addDataScheme("https")
                                        addDataPath("/sub", PatternMatcher.PATTERN_LITERAL)
                                        addDataAuthority(it, null)
                                    }
                            )
                        }
                    },
            )

            whenever(activities) { activityList }
        }

        whenever(getPkg()) { pkg }
        whenever(getName()) { pkgName }
        whenever(this.domainSetId) { domainSetId }
        whenever(getInstantApp(anyInt())) { false }
        whenever(firstInstallTime) { 0L }
    }

    fun DomainVerificationService.addPackages(vararg pkgSettings: PackageSetting) =
            pkgSettings.forEach(::addPackage)
}
