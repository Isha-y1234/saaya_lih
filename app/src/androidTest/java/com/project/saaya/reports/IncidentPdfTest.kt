package com.project.saaya.reports

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.project.saaya.data.remote.*
import com.project.saaya.data.repository.IncidentPdf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only checks. Building this test APK does not mean these tests ran. */
@RunWith(AndroidJUnit4::class)
class IncidentPdfTest {
    @Test fun nativePdfIsReadableMultipageAndUsesScopedProvider() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"report-pdfs/test/report.pdf")
        val report=IncidentReport("test-report","2026-11-06T00:00:00Z",ReportIncident("test-incident","ENDED",null,null,"UTC"),
            (1..100).map{ReportLine(null,"Participant statement (unverified)","परीक्षण $it · non-sensitive test paragraph for native PDF pagination.","test-$it")},
            emptyList(),"Test only",listOf("Audio unavailable"),listOf("Not an official police report"))
        try {
            IncidentPdf.generate(report,file)
            assertEquals("%PDF",file.inputStream().use{input->val prefix=ByteArray(4);assertEquals(4,input.read(prefix));String(prefix)})
            PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use{assertTrue(it.pageCount>1)}
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.evidence",file)
            assertEquals("content",uri.scheme)
        }finally{file.delete()}
    }
    @Test fun providerRejectsFilesOutsideApprovedCacheDirectories() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val outside=File(context.filesDir,"not-exportable.pdf")
        outside.writeText("fixture")
        try{FileProvider.getUriForFile(context,"${context.packageName}.evidence",outside);fail("Broad file access")}
        catch(_:IllegalArgumentException){}finally{outside.delete()}
    }
}
