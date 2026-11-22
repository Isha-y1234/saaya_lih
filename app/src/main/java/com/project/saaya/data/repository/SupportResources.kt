package com.project.saaya.data.repository

data class SupportResource(val name:String,val category:String,val description:String,val phone:String?,val url:String,val source:String,val verified:String="2026-11-06") {
    fun dialUri():String? = phone?.also {require(it.matches(Regex("[0-9]{3,15}")))}?.let {"tel:$it"}
}
/** Bundled offline. Update only from the cited official sources, retaining the verification date. */
object SupportResources {
    const val disclaimer="General information only, not legal advice or legal representation. Availability can vary. No guaranteed response or legal outcome. Saaya does not call, submit complaints or send incident data automatically. Numbers and links can change; check the official source when online."
    val items=listOf(
        SupportResource("112 · Emergency Response Support System","Emergency assistance","Police, fire and medical emergency assistance.","112","https://112.gov.in/","https://www.mha.gov.in/emergency-response-support-system-erss"),
        SupportResource("181 · Women Helpline","Support and referrals","Support and information for women; connections to emergency services and One Stop Centres. Availability may vary by location.","181","https://wcd.gov.in/public/women/help","https://wcd.gov.in/public/women/help"),
        SupportResource("15100 · NALSA","Legal aid","National legal aid helpline. Seek guidance from the legal services authority; Saaya does not provide representation.","15100","https://nalsa.gov.in/womens-assistance/","https://nalsa.gov.in/womens-assistance/"),
        SupportResource("14490 · National Commission for Women","Support and complaints","NCW helpline for issues related to violence against women. For immediate emergencies use 112.","14490","https://ncwapps.nic.in/frmContactUS.aspx","https://ncwapps.nic.in/frmContactUS.aspx"),
        SupportResource("NCW online complaints","Official complaint portal","Review the official instructions and decide whether to submit. No information is sent by Saaya.",null,"https://ncwapps.nic.in/onlinecomplaintsv2/frmPubRegistration.aspx","https://ncwapps.nic.in/onlinecomplaintsv2/frmFAQs.aspx"),
        SupportResource("NALSA legal aid application","Official legal aid portal","Official legal aid application linked by NALSA. Complete and submit only if you choose.",null,"https://scourtapp.nic.in/lsams/nologin/applicationFiling.action?requestLocale=en","https://nalsa.gov.in/womens-assistance/"),
        SupportResource("One Stop Centres","Support and counselling","Official information about medical, legal, temporary shelter, police assistance and psychological/counselling support. Consult official details or 181 for local information.",null,"https://www.wcd.gov.in/offerings/one-stop-centre-scheme","https://www.wcd.gov.in/offerings/one-stop-centre-scheme")
    )
}
