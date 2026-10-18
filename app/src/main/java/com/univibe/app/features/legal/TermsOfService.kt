package com.univibe.app.features.legal

/** Who runs UniVibe and how to reach them. One place, so a new domain or phone number is a one-line change. */
object LegalContact {
    const val OPERATOR = "SwiftNet"
    const val PERSON = "Antony Mwangi"
    /** Temporary address on another project's domain; replace when UniVibe has its own. */
    const val EMAIL = "info@univibe.swiftpesanet.co.ke"
    const val PHONE = "0729612106"
    const val COPYRIGHT = "© UniVibe 2026. All rights reserved."
}

/** A numbered clause ("3.2"), or a bullet when [label] is "•". [heading] may be empty. */
data class LegalClause(val label: String, val heading: String, val text: String)

data class LegalSection(val number: Int, val title: String, val clauses: List<LegalClause>)

/**
 * The Terms of Service shown in the app (sign-up link, Settings → Help & support). The wording describes what UniVibe
 * does today; when a feature is added or removed, change the matching clause here and bump [VERSION] and [UPDATED].
 */
object TermsOfService {
    const val VERSION = "0.1"
    const val UPDATED = "18 October 2026"
    const val TITLE = "Terms of Service"

    private fun c(label: String, heading: String, text: String) = LegalClause(label, heading, text)
    private fun b(text: String) = LegalClause("•", "", text)

    val sections: List<LegalSection> = listOf(
        LegalSection(1, "About these Terms", listOf(
            c("1.1", "Agreement.", "These Terms of Service (“Terms”) govern your use of UniVibe, including the mobile app and related services (the “Service”). By creating an account or using the Service you agree to them. If you do not agree, please do not use the Service."),
            c("1.2", "Who we are.", "UniVibe is operated by ${LegalContact.OPERATOR} (“UniVibe”, “we”, “us”). Our contact details are in Section 24."),
            c("1.3", "Other policies.", "Please read these Terms together with our Privacy Policy, our Community Guidelines (Settings → Help & support) and any feature-specific terms shown to you. If they conflict, the more specific terms apply to that feature, as far as the law allows."),
            c("1.4", "Your legal rights.", "Nothing in these Terms removes or limits a right or remedy that the law does not allow to be removed or limited."),
        )),
        LegalSection(2, "Eligibility", listOf(
            c("2.1", "Who can join.", "UniVibe is for university students. To register you need an email address from a university we support, and you must meet any minimum age that applies to you under the law and under your university’s rules."),
            c("2.2", "Accurate information.", "Give accurate information, keep it reasonably up to date, and do not impersonate anyone or misrepresent your age, identity, university, course or authority."),
            c("2.3", "Legal capacity.", "If you cannot legally enter a binding agreement, you may use the Service only with any consent or supervision the law requires. We may limit features or accounts where needed for safety or legal reasons."),
            c("2.4", "Independence from universities.", "Unless we say otherwise, UniVibe is independent of universities, colleges and student unions. A campus name or logo does not mean the institution endorses UniVibe."),
        )),
        LegalSection(3, "Your account and security", listOf(
            c("3.1", "Creating an account.", "Features such as posting, messaging and events need an account. Do not create accounts to dodge enforcement, deceive people, manipulate engagement or break these Terms. UniVibe is designed for one account per student."),
            c("3.2", "Keeping it secure.", "You are responsible for your password. Tell us promptly at ${LegalContact.EMAIL} if you think someone else has used your account. Do not share, sell or transfer your account."),
            c("3.3", "Verification.", "We confirm your email with a code we send you. We do not currently offer two-factor authentication. Verification does not guarantee that someone is trustworthy or that what they post is accurate."),
            c("3.4", "Logging in on several devices.", "You can see the devices you are logged in on and log out the others in Settings → Security → Login activity. Changing your password logs out your other devices."),
            c("3.5", "Actions on your account.", "Actions taken through your account may be treated as yours, unless it was used without your permission and you told us promptly, subject to the law and the circumstances."),
        )),
        LegalSection(4, "Campus identity and verification", listOf(
            c("4.1", "What “verified student” means.", "Student verification currently means that the university email you confirmed matches a university we support. It does not check identity documents, and it does not guarantee a person’s conduct, academic standing, safety or continuing enrolment."),
            c("4.2", "Honesty.", "Do not misrepresent your current university or course, and do not submit another person’s student card, credentials or personal information without lawful authority."),
            c("4.3", "No false representation.", "Do not impersonate staff or falsely claim to represent a university, college, club or student body."),
        )),
        LegalSection(5, "The Service and changes to it", listOf(
            c("5.1", "Features.", "Depending on availability, UniVibe provides profiles, posts with photos, feeds, follows, comments, reactions, polls, search, Discover, communities, events, direct and group messages (including voice messages), notifications, and safety tools such as blocking and reporting."),
            c("5.2", "Availability and changes.", "We may maintain, improve, test, suspend or stop features for security, legal, technical or operational reasons. Where reasonably possible we will tell you about material changes that significantly affect you."),
            c("5.3", "Preview features.", "Anything labelled beta, preview or experimental may be incomplete or change without notice. Do not rely on it as the only place you keep something important."),
            c("5.4", "Reach.", "We do not promise that a post will reach a particular number of people, appear in a feed, get engagement, or stay available for a particular time."),
            c("5.5", "Your device and data.", "You are responsible for a compatible device, network access, and any data or carrier charges. Some features need Android permissions."),
        )),
        LegalSection(6, "Your content", listOf(
            c("6.1", "What counts.", "“User Content” is anything you submit or share through the Service: text, photos, profile information, comments, polls, event details and messages."),
            c("6.2", "You own it.", "You keep whatever ownership rights you have in your User Content, subject to the licence in Section 7 and the rights of others."),
            c("6.3", "Only share what you may.", "Share content only if you own it or have the permissions and rights you need. Respect privacy, confidentiality, copyright, trademarks and other rights."),
            c("6.4", "You are responsible.", "You are responsible for what you share and its consequences. Check important claims, especially allegations about identifiable people, safety notices, event details and announcements."),
            c("6.5", "Audience and copies.", "Your content may be seen by the audience you choose, message recipients and group members, depending on the feature and your settings. People who can see content may copy, capture or forward it."),
            c("6.6", "Backups.", "Keep your own copies of anything important. UniVibe does not promise permanent storage or recovery."),
        )),
        LegalSection(7, "Licence you give UniVibe", listOf(
            c("7.1", "Licence to run the Service.", "You give UniVibe a non-exclusive, worldwide, royalty-free licence to host, store, copy, process, format, transmit and display your User Content only as reasonably needed to run, secure and provide the Service and the features you use. It lasts while we handle the content for those purposes, subject to deletion, backups, legal retention and our Privacy Policy."),
            c("7.2", "Examples.", "This lets us show a post to the audience you chose, deliver a message to its recipients, resize a photo, or include content in search or a feed."),
            c("7.3", "Ownership stays with you.", "The licence does not transfer ownership. We will not publicly display your private messages or private media for unrelated purposes just because you sent them through the Service."),
            c("7.4", "Feedback.", "If you send us suggestions, we may use them to improve the Service without paying you, as far as the law allows."),
        )),
        LegalSection(8, "Acceptable use", listOf(
            c("8.1", "General rule.", "Use UniVibe lawfully and respectfully, without harming other people’s safety, privacy, rights or ability to use the Service."),
            c("8.2", "Do not:", ""),
            b("harass, threaten, stalk, intimidate or repeatedly target another person;"),
            b("post credible threats, encourage violence or organise real-world harm;"),
            b("share intimate images or private sexual content without valid consent, or exploit or endanger a child;"),
            b("expose someone’s private data, address, phone number, student details, credentials or location without lawful authority;"),
            b("impersonate others, fake verification, scam, phish or run deceptive fundraising;"),
            b("post unlawful, defamatory, infringing or discriminatory content;"),
            b("spam, fake engagement, or use bots to manipulate popularity or send bulk messages;"),
            b("spread malware, exploit vulnerabilities, disrupt the Service, scrape it without permission or bypass its safeguards;"),
            b("access accounts, private conversations or systems without permission;"),
            b("evade blocks, suspensions, moderation controls or rate limits;"),
            b("misuse reporting, review or support systems, including by making false reports;"),
            b("use events, polls, groups or communities to facilitate unlawful activity."),
            c("8.3", "Opinions.", "What users post does not necessarily reflect UniVibe’s views or those of any university."),
            c("8.4", "Reporting and emergencies.", "Use in-app reporting, or email ${LegalContact.EMAIL}, to report possible breaches. If someone is in immediate danger, contact local emergency services (in Kenya, 999 or 112) instead of relying on an in-app report."),
        )),
        LegalSection(9, "Messages and groups", listOf(
            c("9.1", "Messaging.", "UniVibe offers direct and group messages, photos, voice messages, reactions, typing indicators and push notifications. Check who you are sending to, and who is in a group, before you send anything sensitive."),
            c("9.2", "Controls.", "You can choose who can message you, block people and report messages. These controls do not take back messages already delivered or copies others have kept."),
            c("9.3", "Storage.", "Messages are stored so we can deliver them, show them on your devices, prevent abuse, look into reports and comply with the law, as described in our Privacy Policy. Deleting a message from your view may not delete other people’s copies or our backups."),
            c("9.4", "Not end-to-end encrypted.", "UniVibe messages are not end-to-end encrypted. They are stored on our servers, and we take reasonable measures to protect them (see Section 10). Please do not send passwords or highly sensitive information in messages."),
            c("9.5", "Notifications.", "Notifications may show a sender’s name or part of a message, depending on your phone’s settings. Android settings can override preferences you set in UniVibe."),
            c("9.6", "Group admins.", "Group admins can manage members, names, images and other settings the app gives them. Being in a group does not give anyone access beyond what the Service allows."),
            c("9.7", "No misuse.", "Do not use messages to harass, threaten, scam, share prohibited content, or get around someone’s choices about who can contact them."),
        )),
        LegalSection(10, "Privacy, data and permissions", listOf(
            c("10.1", "Privacy Policy.", "Our Privacy Policy explains what personal data we collect, why, who receives it, how long we keep it, how we protect it, and how you can use your rights."),
            c("10.2", "What we process.", "We process information needed to create and secure accounts, deliver posts and messages, show feeds, send notifications, prevent abuse, fix errors and run the Service."),
            c("10.3", "Permissions.", "The app may ask for permission to send notifications and, the first time you record a voice message, to use your microphone. It does not need permission to read your photos, because you pick each photo yourself. You can say no or withdraw a permission in Android settings, though some features may then stop working. We ask only when a feature needs it."),
            c("10.4", "Location and contacts.", "UniVibe does not collect your precise location and does not upload your phone contacts. Event venues you see on a map are places that organisers entered, not your location."),
            c("10.5", "Security.", "We use reasonable technical and organisational measures to protect personal data. No system is completely secure, and we cannot promise absolute security."),
            c("10.6", "Legal requests.", "We may keep or share information where the law requires, or where reasonably necessary to protect users, enforce these Terms, investigate fraud or protect the Service, subject to legal requirements."),
            c("10.7", "Your data rights.", "Depending on the law that applies to you, you may have rights to access, correct, delete, object to, or get a copy of your personal data. Contact ${LegalContact.EMAIL}."),
        )),
        LegalSection(11, "Feeds, search and moderation", listOf(
            c("11.1", "What you see.", "Feeds, Discover and search results may use things like recency, who you follow, your campus, interactions, safety rules and your settings. Changes to your settings may take time to show."),
            c("11.2", "Moderation.", "We may use reports, automated tools and human review to assess content or conduct that may break these Terms or the law."),
            c("11.3", "Actions we may take.", "These include removing content, limiting features, issuing warnings, temporarily suspending an account, or ending it permanently. You can see your account’s standing and any notices in Settings → Account status."),
            c("11.4", "Asking us to look again.", "If you think a decision was a mistake, email ${LegalContact.EMAIL} and we will review it. Where safety, security or the law requires, we may act first and explain afterwards."),
            c("11.5", "Limits.", "Moderation can make mistakes, and we cannot promise to find or remove every prohibited item. Report urgent concerns."),
        )),
        LegalSection(12, "Intellectual property", listOf(
            c("12.1", "Our materials.", "The software, branding, logos and designs of UniVibe belong to us or our licensors and are protected by law. Unless the law or we allow it, do not copy, modify, distribute, reverse engineer or commercially exploit them."),
            c("12.2", "Your licence to use the app.", "While you follow these Terms, we give you a limited, personal, non-exclusive, non-transferable, revocable licence to use the Service for its intended purpose."),
            c("12.3", "Reporting infringement.", "If you believe content infringes your copyright or other rights, email ${LegalContact.EMAIL} with details of the work, where the content is, your contact details, your rights and what you want us to do. We may ask for more information and may restrict the disputed content."),
            c("12.4", "False reports.", "Do not knowingly send false, misleading or abusive legal or intellectual-property reports."),
        )),
        LegalSection(13, "Events, polls and campus information", listOf(
            c("13.1", "Accuracy.", "Organisers and users are responsible for the descriptions, dates, locations, eligibility, prices and safety information they post."),
            c("13.2", "No guarantee.", "Unless we say otherwise, events, polls, communities and announcements are provided by users or third parties and are not guaranteed to be official, accurate, safe or endorsed."),
            c("13.3", "Staying safe.", "Use your own judgement when attending events or meeting people. UniVibe is not a background-check, emergency or physical-safety service."),
            c("13.4", "Polls.", "Poll results can be affected by who takes part, invalid votes, moderation or technical issues. Polls are informal and are not official elections or surveys."),
            c("13.5", "Paid events.", "UniVibe does not currently handle tickets or payments. If that changes, separate terms will explain prices, fees, refunds and disputes before anything can be bought."),
        )),
        LegalSection(14, "Third-party services", listOf(
            c("14.1", "Services we rely on.", "The Service uses third-party services. For example, push notifications are delivered through Google’s Firebase, and maps use OpenStreetMap data. Their own terms and privacy practices may apply."),
            c("14.2", "No control.", "Except where the law says otherwise, we are not responsible for third-party services we do not control. A link or integration is not an endorsement."),
            c("14.3", "App stores.", "Your use of the Android app may also be subject to the terms of the store you got it from."),
        )),
        LegalSection(15, "Cost", listOf(
            c("15.1", "Free to use.", "UniVibe is currently free to use."),
            c("15.2", "Future paid features.", "If we ever add paid features, we will show the price, billing period, renewal, cancellation and refund rules before you pay, and get any consent the law requires. We will not charge you without clear notice and your confirmation."),
        )),
        LegalSection(16, "Suspension and ending access", listOf(
            c("16.1", "Your choice.", "You can stop using UniVibe at any time and delete your account (Section 17)."),
            c("16.2", "Our actions.", "We may restrict, suspend or end your access if you materially or repeatedly break these Terms, create a safety or security risk, break the law, or where legal or operational reasons require."),
            c("16.3", "Proportionate response.", "Where practical we will consider how serious the problem is, how often it happened and the context. Urgent cases may need immediate action."),
            c("16.4", "Review.", "You can ask us to review an enforcement decision by emailing ${LegalContact.EMAIL}. We may ask for information we reasonably need to assess it."),
            c("16.5", "What happens next.", "When access ends, your right to use the Service ends. Provisions that by nature should continue (such as intellectual-property rights, disclaimers, legal limits on liability and disputes) continue to apply."),
        )),
        LegalSection(17, "Deleting your account and your data", listOf(
            c("17.1", "How to delete.", "Go to Settings → Account → Delete account. You will need to enter your password and confirm. Deletion is permanent."),
            c("17.2", "What deletion does.", "Deleting your account removes your account and the content tied to it from our systems. Some information may remain for a limited time in backups, or where we must keep it for legal, security or fraud-prevention reasons. Our Privacy Policy gives details."),
            c("17.3", "Copies held by others.", "Content you shared may remain visible to people who received it, in their screenshots or downloads, or on independent services. We cannot remove copies we do not control."),
            c("17.4", "Deactivation and export.", "UniVibe does not currently offer temporary deactivation or a self-service data export. To ask for a copy of your data, email ${LegalContact.EMAIL}; we may need to verify that the account is yours."),
            c("17.5", "Different from logging out.", "Logging out, clearing app data and deleting your account are different things. Only deleting your account removes your data from our servers."),
        )),
        LegalSection(18, "Disclaimers", listOf(
            c("18.1", "Availability.", "To the extent the law allows, the Service is provided “as available”. We do not promise it will be uninterrupted, error-free, secure or work on every device."),
            c("18.2", "User content.", "We do not guarantee the accuracy, legality, quality or safety of User Content, or the identity, conduct, qualifications or intentions of other users."),
            c("18.3", "Not professional advice.", "Content on UniVibe is for general social and informational use. It is not medical, legal, financial, academic, emergency or other professional advice."),
            c("18.4", "Your rights.", "These disclaimers apply only as far as the law allows and do not remove consumer or other protections that cannot be removed."),
        )),
        LegalSection(19, "Limits on our liability", listOf(
            c("19.1", "Indirect losses.", "To the maximum extent the law allows, UniVibe is not liable for indirect, incidental, special, exemplary, punitive or consequential loss, or for loss of profits, goodwill, opportunity or data arising from your use of, or inability to use, the Service."),
            c("19.2", "What is not limited.", "Nothing in these Terms excludes or limits liability that cannot lawfully be excluded or limited, including liability for fraud and rights you have as a consumer."),
        )),
        LegalSection(20, "Responsibility for your conduct", listOf(
            c("20.1", "Your responsibility.", "To the extent the law allows, you may be responsible for reasonable losses, liabilities and costs that arise directly from your unlawful conduct, your infringement of someone’s rights, or your material breach of these Terms. This does not apply where consumer law does not allow it."),
            c("20.2", "Claims.", "If a claim is made, we will tell you where reasonably practicable and give you a reasonable chance to respond."),
        )),
        LegalSection(21, "Disputes and governing law", listOf(
            c("21.1", "Talk to us first.", "Before starting formal proceedings, email ${LegalContact.EMAIL} describing the problem and what you would like us to do. We will try to resolve genuine concerns fairly. This does not stop urgent legal action or remove your legal rights."),
            c("21.2", "Governing law.", "These Terms are intended to be governed by the laws of Kenya, subject to any mandatory protections that apply to you."),
            c("21.3", "Regulators.", "Nothing stops you from contacting a competent regulator or using a legal remedy that cannot lawfully be restricted."),
        )),
        LegalSection(22, "Changes to these Terms", listOf(
            c("22.1", "Updates.", "We may update these Terms to reflect changes to the Service, the law, security practices or our business."),
            c("22.2", "Notice.", "For material changes we will give reasonable notice in the app or by email where required, with the effective date and a summary of important changes where appropriate."),
            c("22.3", "Acceptance.", "Where required, we will ask you to accept the updated Terms before you keep using the affected features. If you disagree, stop using the Service; you can delete your account."),
        )),
        LegalSection(23, "General", listOf(
            c("23.1", "Whole agreement.", "These Terms and the policies they refer to are the agreement between you and UniVibe about the Service, plus any extra terms for particular features."),
            c("23.2", "If part is unenforceable.", "If a provision cannot be enforced, it is limited or removed only as far as necessary and the rest continues."),
            c("23.3", "No waiver.", "If we do not enforce a provision straight away, we can still enforce it later."),
            c("23.4", "Transfers.", "You may not transfer your rights or obligations under these Terms without our consent. We may transfer them as part of a lawful restructuring, merger or transfer of the Service."),
            c("23.5", "No partnership.", "These Terms do not make you and UniVibe employer and employee, agents, partners or joint venturers."),
            c("23.6", "Notices.", "We may send service notices electronically. We will respect consent and opt-out rules for marketing messages."),
        )),
        LegalSection(24, "Contact", listOf(
            c("24.1", "", "Operator: ${LegalContact.OPERATOR}"),
            c("24.2", "", "Contact person: ${LegalContact.PERSON}"),
            c("24.3", "", "Email (support, privacy, legal, copyright and safety reports): ${LegalContact.EMAIL}"),
            c("24.4", "", "Phone: ${LegalContact.PHONE}"),
        )),
        LegalSection(25, "Community rules in short", listOf(
            b("Be respectful. Do not harass, threaten, stalk or target people."),
            b("Protect privacy. Do not expose private personal, academic, contact or location information without lawful authority."),
            b("Share responsibly. Do not impersonate others, scam people or knowingly spread harmful falsehoods."),
            b("Respect consent. Do not share intimate or private material without permission."),
            b("Keep campus safe. Do not use groups, events, polls or messages to organise violence, abuse or unlawful activity."),
            b("Respect intellectual property. Post only what you have the right to share."),
            b("No manipulation or abuse. Do not spam, fake engagement, attack the Service or evade enforcement."),
            b("Report concerns honestly, and use the review route if you think we got something wrong."),
        )),
    )

    /** Everything as plain text, for tests and for any future share or copy action. */
    fun plainText(): String = buildString {
        append(TITLE).append(" (version ").append(VERSION).append(", ").append(UPDATED).append(")\n")
        for (s in sections) {
            append('\n').append(s.number).append(". ").append(s.title).append('\n')
            for (cl in s.clauses) {
                append(cl.label).append(' ')
                if (cl.heading.isNotEmpty()) append(cl.heading).append(' ')
                append(cl.text).append('\n')
            }
        }
        append('\n').append(LegalContact.COPYRIGHT)
    }
}
