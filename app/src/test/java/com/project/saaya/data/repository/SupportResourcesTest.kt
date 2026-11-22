package com.project.saaya.data.repository

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class SupportResourcesTest {
    @Test fun bundledVerifiedNationalNumbersAndDatesAvailableWithoutNetwork() {
        assertEquals(setOf("112","181","15100","14490"),SupportResources.items.mapNotNull{it.phone}.toSet())
        assertTrue(SupportResources.items.all{it.verified=="2026-11-06" && it.description.isNotBlank()})
        assertTrue(SupportResources.disclaimer.contains("not legal advice"))
    }
    @Test fun websitesAndSourcesRemainOnVerifiedOfficialDomains() {
        val domains=setOf("112.gov.in","www.mha.gov.in","wcd.gov.in","www.wcd.gov.in","nalsa.gov.in","ncwapps.nic.in","scourtapp.nic.in")
        for(resource in SupportResources.items)for(link in listOf(resource.url,resource.source)) {
            val uri=URI(link);assertEquals("https",uri.scheme);assertTrue(domains.contains(uri.host));assertNull(uri.userInfo)
        }
    }
    @Test fun dialTargetsOnlyContainUserSelectedPhoneNumbers() {
        SupportResources.items.forEach{resource->assertEquals(resource.phone?.let{"tel:$it"},resource.dialUri())}
        assertEquals(3,SupportResources.items.count{it.dialUri()==null})
    }
}
