package com.univibe.app.features.legal

/**
 * The Privacy Policy shown in the app (sign-up link, Settings → Help & support, Settings → About). Contact details
 * come from [LegalContact], shared with the Terms of Service. The wording describes what UniVibe does today: when a
 * feature, provider or retention period changes, change the matching clause here and bump [VERSION] and [UPDATED].
 */
object PrivacyPolicy {
    const val VERSION = "0.1"
    const val UPDATED = "18 October 2026"
    const val TITLE = "Privacy Policy"

    private fun c(label: String, heading: String, text: String) = LegalClause(label, heading, text)
    private fun b(text: String) = LegalClause("•", "", text)

    val sections: List<LegalSection> = listOf(
        LegalSection(1, "About this Policy", listOf(
            c("1.1", "Who we are.", "UniVibe is operated by ${LegalContact.OPERATOR} (“UniVibe”, “we”, “us”). We decide why and how your personal data is used in the UniVibe app and its related services (the “Service”). Our contact details are in Section 24."),
            c("1.2", "What this covers.", "This Policy explains what personal data we collect, why, who receives it, how long we keep it, how we protect it, and the choices and rights you have. It applies to the UniVibe Android app and the servers behind it."),
            c("1.3", "Other documents.", "Please read it with our Terms of Service and Community guidelines (Settings → Help & support). Some features may have a short notice of their own when they need extra data."),
            c("1.4", "The law.", "We aim to follow Kenya’s Data Protection Act, 2019 and the rules made under it, and other laws that apply to you. Where the law gives you a right this Policy does not mention, you still have it."),
        )),
        LegalSection(2, "Our approach", listOf(
            c("2.1", "Only what we need.", "We collect the information needed to run UniVibe and keep it safe. We ask for a phone permission only when a feature needs it."),
            c("2.2", "No selling, no ads.", "We do not sell your personal data. UniVibe does not show advertising and does not use advertising or analytics tools to track you across apps or websites."),
            c("2.3", "Honest descriptions.", "This Policy describes what UniVibe does today, not what we may do one day. If that changes, we will update it first (Section 23)."),
            c("2.4", "No system is perfectly secure.", "We protect your data with reasonable safeguards (Section 17), but please do not share anything through UniVibe that you would not be comfortable with the people you send it to seeing."),
        )),
        LegalSection(3, "What personal data we collect", listOf(
            c("3.1", "Account details.", "Your full name, university email address, university, password (kept only as a scrambled hash, never as plain text), an optional admission number if you enter one, the date you accepted the Terms, and whether your email and student status are verified."),
            c("3.2", "Profile details.", "Your username (handle), bio, course, faculty, year of study, graduation year, interests, and your profile and cover photos, to the extent you add them."),
            c("3.3", "Content you create.", "Posts and their photos or videos, captions, polls, comments, events you create, and anything else you submit. Content can mention or show other people; please respect their privacy."),
            c("3.4", "Activity.", "Who you follow and who follows you, likes, saves, poll votes, event registrations and check-ins, people you block, Discover choices (pass or connect), notification and privacy settings, and your reports."),
            c("3.5", "Messages.", "Direct and group conversations you are in, the messages, photos and voice messages you send, replies, forwards, reactions, pinned messages, who is in each conversation, when messages were sent, edited or unsent, and how far you have read each conversation (we need this for unread counts and delivery)."),
            c("3.6", "Notifications and devices.", "In-app notifications about your activity, your notification choices, and for push notifications a device token and installation ID. When you log in we store a short device label such as the make and model of your phone, so you can recognise your logins in Settings → Security → Login activity."),
            c("3.7", "Support and safety.", "Emails you send us, reports you make or that concern you (including the reason, any details and a short excerpt of the reported item), moderation decisions and notes, and a record of moderator actions."),
            c("3.8", "Technical data.", "Your IP address and basic request details are seen by our servers and hosting provider whenever the app connects, and are used for security and rate limiting. See Section 11."),
        )),
        LegalSection(4, "What we do not collect", listOf(
            c("4.1", "Not collected.", "UniVibe does not currently collect or ask for:"),
            b("your location in the background, or your location at any time you haven't asked us to use it. We only look at where you are when you tap \"Send location\" in a chat or \"Use my location\" on a map, and only while the app is open;"),
            b("your phone contacts or camera, or your microphone at any time other than while you are recording a voice message you chose to send;"),
            b("identity documents, student cards or government ID numbers;"),
            b("payment details (UniVibe is free and has no payments);"),
            b("an advertising ID, or data for advertisers or data brokers."),
            c("4.2", "If this changes.", "If we add a feature that needs any of this, we will tell you in this Policy and in the app before it starts, and ask for your permission where the law or Android requires it."),
        )),
        LegalSection(5, "How we collect it", listOf(
            c("5.1", "From you.", "When you sign up, verify your email, edit your profile, post, message, create events, change settings, report something or contact us."),
            c("5.2", "Automatically.", "When you use the app, your phone and our servers exchange the information needed to log you in, show your feed, deliver messages and send notifications. Servers also keep security and error records."),
            c("5.3", "From other people.", "Other students may follow you, mention you, message you, add you to a group, invite you to an event or report something that involves you."),
            c("5.4", "From our providers.", "For example, our email provider tells us whether a message was accepted for sending, and Google’s push service tells us when a device token is no longer valid."),
        )),
        LegalSection(6, "Why we use your data", listOf(
            c("6.1", "To run the Service.", "To create and secure your account, confirm your university email, show profiles, feeds and events, deliver messages, and apply the settings you choose. Our legal basis is performing our agreement with you (the Terms)."),
            c("6.2", "To keep UniVibe safe.", "To prevent spam, impersonation, fraud and abuse, check reports, enforce the Terms and respond to security problems. Our legal basis is our legitimate interest in a safe community, balanced against your rights, and sometimes a legal duty."),
            c("6.3", "To send notifications and emails.", "To send alerts you have not turned off, and emails that the Service needs: verification codes and password resets."),
            c("6.4", "To improve reliability.", "To find and fix errors and keep the Service running. We use technical records for this, not tracking tools."),
            c("6.5", "To follow the law.", "To keep records, answer valid legal requests and protect legal rights."),
            c("6.6", "With your consent.", "Where we rely on consent, such as allowing notifications on your phone, you can withdraw it at any time. This does not affect what was done before you withdrew."),
            c("6.7", "Nothing unrelated.", "We will not use your data for a new purpose that is not compatible with these, unless we tell you first and get any consent the law requires."),
        )),
        LegalSection(7, "Who can see your content", listOf(
            c("7.1", "Students only.", "Profiles and posts are shown to signed-in UniVibe students, not to the public web or search engines."),
            c("7.2", "Post audiences.", "When you post you choose an audience: your campus (students at your university) or only your followers. Comments, likes, poll votes and event registrations can be seen by people who can see the item they relate to."),
            c("7.3", "Discover and online status.", "You can choose whether you appear in Discover, whether others can see when you are online, and who can message you (everyone, people you follow, or nobody) in Settings → Privacy & safety."),
            c("7.4", "Copies.", "People who can see something can copy, screenshot or forward it. Deleting it from UniVibe cannot remove copies other people made."),
            c("7.5", "Keep it sensible.", "Avoid putting sensitive information (such as health, religion, politics, your address or your ID numbers) in your profile or posts. A campus audience is not private."),
        )),
        LegalSection(8, "Messages and groups", listOf(
            c("8.1", "How we handle them.", "We store messages and photos on our servers so we can deliver them, show them on your devices, and support blocking, reporting and abuse checks."),
            c("8.2", "Not end-to-end encrypted.", "UniVibe messages are not end-to-end encrypted. We take reasonable steps to protect them (Section 17), but they are stored in a form our systems can read. Please do not send passwords or highly sensitive information in messages."),
            c("8.3", "Who can read them.", "The people in the conversation can read it. We do not read private messages as a matter of routine. Authorised moderators or safety staff may look at a message or conversation when someone reports it, or where needed to investigate abuse, a security problem or a legal request."),
            c("8.4", "Groups.", "Members of a group can see its messages, name, image and member list. A member can copy or forward what you send. Leaving a group does not remove messages already delivered."),
            c("8.5", "Unsending.", "If you unsend a message, its text is removed from our database and it is replaced by a “deleted” notice in the conversation. People may already have read it or kept a copy, and a message that was reported may be kept as part of the report."),
            c("8.6", "Typing, online status and read marks.", "Typing indicators are held briefly in memory and never stored. Online status is shown only if you leave it switched on. We record how far you have read each conversation. The Read receipts switch in Settings affects whether your app shows you “seen” marks on messages you sent; it does not stop us recording what you have read."),
            c("8.7", "Messages you sent when you leave UniVibe.", "If you delete your account, messages you sent to other people may remain in their conversations, no longer linked to your account (Section 16)."),
        )),
        LegalSection(9, "Notifications and device information", listOf(
            c("9.1", "Push notifications.", "We send push notifications through Google’s Firebase Cloud Messaging. Google receives your device’s push token and delivery details to route them."),
            c("9.2", "What a push contains.", "A push message from us carries only identifiers (such as the type of alert and which conversation it belongs to), not the text of your message. Your app then fetches what it needs from our servers and shows the notification."),
            c("9.3", "Your choices.", "You can turn alerts on or off by type, and set quiet hours, in Settings → Notifications. You can also manage notifications in Android settings, which override UniVibe’s."),
            c("9.4", "Lock screen.", "Notifications can show a sender’s name or part of a message on your lock screen, depending on your phone’s settings. Change your Android lock-screen notification setting if you want to hide this."),
            c("9.5", "Device tokens.", "We stop using a push token that has not worked or been refreshed for 90 days, and delete it 30 days after we revoke it. We also delete tokens when you delete your account, or when Google tells us they are no longer valid."),
            c("9.6", "Logging in on several devices.", "The device label described in Section 3.6 is shown only to you, for you to recognise your logins. We never use it to make decisions about you, and we do not store your IP address or location against a login."),
        )),
        LegalSection(10, "Permissions on your phone", listOf(
            c("10.1", "What the app asks for.", "UniVibe uses the internet and, on newer versions of Android, asks permission to show notifications and, the first time you record a voice message, to use the microphone. The microphone is used only while you are recording, and only the voice message you send is kept. UniVibe does not ask for your location, contacts or camera."),
            c("10.2", "Photos.", "When you add a photo or video, you choose which one to upload. UniVibe does not ask for access to your whole photo library."),
            c("10.3", "Withdrawing permissions.", "You can change permissions in Android settings at any time. If you turn off notifications, you may miss alerts, but the rest of UniVibe keeps working."),
            c("10.4", "Maps.", "Event pages can show a map. The map is drawn with data from OpenStreetMap, so your phone contacts OpenStreetMap’s servers to download map tiles for the area you are looking at, and they can see your IP address. UniVibe does not send them your account details."),
            c("10.5", "Backups.", "UniVibe turns off Android’s automatic app backup, so your UniVibe data is not copied into your Google backup."),
        )),
        LegalSection(11, "Technical data, analytics and cookies", listOf(
            c("11.1", "No analytics or ad tools.", "The app does not include analytics, advertising or crash-reporting services, and the Service does not use cookies to track you."),
            c("11.2", "Server records.", "Our servers and hosting provider keep technical logs of requests and errors. These can include your IP address, the time and the kind of request. We use them to keep the Service secure and working. We try not to place passwords, codes or message text in them."),
            c("11.3", "Rate limiting.", "To stop abuse, we count recent attempts per IP address and per account (for example, sign-up and login attempts). These counts are held in memory for a short time."),
            c("11.4", "On your phone.", "The app stores your login securely using Android’s Keystore and keeps your settings (such as theme and notification choices) on the device. Logging out removes your login from the device."),
        )),
        LegalSection(12, "Feeds, Discover and recommendations", listOf(
            c("12.1", "What we use.", "Feeds, Discover and search use simple signals such as recency, who you follow, your university, your interactions, the interests you added, your Discover choices, safety rules and your privacy settings."),
            c("12.2", "Your control.", "You can choose whether you appear in Discover, mute or block people, and change your interests. We do not use your messages to decide what you see, and we do not use special categories of data, such as health or religion, for recommendations."),
            c("12.3", "Automated decisions.", "We do not make decisions that have legal or similarly significant effects on you purely by automated means. Moderation uses reports and review by people."),
        )),
        LegalSection(13, "Who we share data with", listOf(
            c("13.1", "Other students.", "We show your content to the people you chose as the audience, and to recipients and group members you message."),
            c("13.2", "Companies that help us run UniVibe.", "They process data for us under their own terms, only to provide their service:"),
            b("Render: runs our server (the UniVibe API);"),
            b("Neon: hosts our database (accounts, profiles, posts, messages and settings);"),
            b("Cloudflare R2: stores photos and videos;"),
            b("Resend: sends verification and password-reset emails (receives your email address, first name and the code);"),
            b("Google Firebase Cloud Messaging: delivers push notifications (receives your device token and the identifiers in the message);"),
            b("OpenStreetMap: provides map tiles (see Section 10.4)."),
            c("13.3", "Legal and safety.", "We may disclose information where the law requires, in response to a valid legal request, or where reasonably necessary to protect people, investigate fraud or abuse, enforce the Terms or protect the Service."),
            c("13.4", "Business changes.", "If UniVibe is restructured, merged or transferred, your data may move to the new owner, which must respect this Policy or tell you first."),
            c("13.5", "At your request.", "We share information when you ask us to or use a feature meant to share it."),
            c("13.6", "No sale.", "We do not sell your personal data or share it with advertisers."),
        )),
        LegalSection(14, "Transfers outside Kenya", listOf(
            c("14.1", "Where data goes.", "Our providers (Section 13.2) are international companies and may store or process data in other countries, such as the United States or Europe. Cloudflare and Google operate global networks."),
            c("14.2", "Safeguards.", "Where data leaves Kenya, we rely on the protections the law allows, such as contracts with these providers, and on your consent where the law requires it. We will tell you if we move your data to a place with weaker protection."),
        )),
        LegalSection(15, "How long we keep data", listOf(
            c("15.1", "Principle.", "We keep personal data only as long as we need it for the purposes above, or as the law requires."),
            c("15.2", "Your account and content.", "We keep your account, profile, posts, comments, activity and settings until you delete your account (Section 16)."),
            c("15.3", "Logins.", "A login session expires 30 days after it was last used, and access to your account is checked every 15 minutes. Changing your password logs out your other devices."),
            c("15.4", "Codes.", "Verification and password-reset codes expire after 10 minutes and allow only a few attempts. We store them only in scrambled form."),
            c("15.5", "Notifications.", "In-app notifications you have read are deleted after 180 days. Push tokens follow Section 9.5."),
            c("15.6", "Background records.", "Records of finished notification jobs are deleted after 7 days, and failed ones after 30 days."),
            c("15.7", "Messages.", "Messages are kept in conversations until they are unsent (Section 8.5) or the conversation is removed, subject to Section 16.3."),
            c("15.8", "Reports and moderation.", "We keep reports, moderator notes and action records for as long as needed to protect users, prevent repeat abuse, review appeals and meet legal duties, and we review them from time to time."),
            c("15.9", "Server logs and backups.", "Technical logs and database backups are kept by our hosting providers for a limited time under their own settings. Deleted data may remain in backups until they expire, and is not restored to active use except to recover the Service."),
            c("15.10", "Legal holds.", "We may keep information longer where needed for a legal claim, a security investigation or a legal obligation."),
        )),
        LegalSection(16, "Deleting your account and your data", listOf(
            c("16.1", "How.", "Go to Settings → Account → Delete account. You will need to enter your password and confirm. Deletion is permanent. If you cannot do this, email ${LegalContact.EMAIL}; we may need to verify the account is yours."),
            c("16.2", "What is removed.", "Deleting your account removes your profile, posts, comments, likes, follows, settings, notifications, login sessions and push tokens from our database, and we delete the photos and videos you uploaded from our storage."),
            c("16.3", "What may remain.", "Messages you sent to other people can remain in their conversations without your name. Reports and moderation records involving you may be kept for safety and legal reasons, detached from your account where our systems allow. Copies other people made, and data in backups until they expire, are not removed immediately."),
            c("16.4", "Different from logging out.", "Logging out or clearing app data only affects your phone. Only deleting your account removes your data from our servers."),
            c("16.5", "No deactivation or export tool.", "UniVibe does not currently offer temporary deactivation or a self-service data export. To ask for a copy of your data, email ${LegalContact.EMAIL}."),
        )),
        LegalSection(17, "Security", listOf(
            c("17.1", "What we do.", "Our safeguards include:"),
            b("passwords are stored only as Argon2id hashes, never as readable text;"),
            b("verification and reset codes are stored only in scrambled form, expire after 10 minutes and allow only a few attempts;"),
            b("logins use short-lived access tokens and renewable session tokens, and you can end other sessions in Settings → Security → Login activity;"),
            b("data travels between the app and our servers over an encrypted HTTPS connection, and the app keeps your login in the Android Keystore;"),
            b("rate limits slow down password guessing and abuse;"),
            b("photos and videos are kept in private storage and are shown through temporary links, not public addresses;"),
            b("access to personal data is limited to people who need it to run, secure and moderate the Service."),
            c("17.2", "Your part.", "Choose a strong, unique password, keep your phone secure and updated, and tell us quickly if you think someone else has used your account."),
            c("17.3", "Reporting a weakness.", "If you find a security problem, email ${LegalContact.EMAIL}. Please do not access other people’s data or disrupt the Service while testing."),
        )),
        LegalSection(18, "Your rights and choices", listOf(
            c("18.1", "Your rights.", "Under the law that applies to you, including Kenya’s Data Protection Act, 2019, you may have the right to be told how your data is used, to access it, to correct it, to delete it, to object to or restrict some uses, to receive a copy in a usable format, and to withdraw consent."),
            c("18.2", "How to use them.", "Email ${LegalContact.EMAIL}. We may need to check it is really you. We will reply within the time the law requires."),
            c("18.3", "Controls in the app.", "You can edit your profile (Edit profile), change your password and manage logins (Settings → Security), choose your audience and who can message you (Settings → Privacy & safety), block people, choose your notifications (Settings → Notifications), and delete your account (Settings → Account)."),
            c("18.4", "Limits.", "We may be unable to grant a request in full where the law allows or requires us to keep information, or where it would affect other people’s rights, for example in a safety investigation."),
            c("18.5", "Complaints.", "Please contact us first so we can try to put things right. You also have the right to complain to the Office of the Data Protection Commissioner of Kenya, or to the regulator in the country where you live."),
        )),
        LegalSection(19, "Blocking, reporting and safety", listOf(
            c("19.1", "Blocking.", "Blocked students are hidden from your feed and cannot message you. The block list is private to you."),
            c("19.2", "Reports.", "When you report a person, post, comment, message or event, we receive your reason, any details you add, and a short excerpt of the reported item so that a moderator can assess it."),
            c("19.3", "Who sees reports.", "Reports are visible only to moderators and administrators. We do not tell the reported person who reported them, unless the law requires it."),
            c("19.4", "Records.", "Moderator actions (such as removing content or suspending an account) are logged, and you can see notices about your own account in Settings → Account status."),
            c("19.5", "Emergencies.", "Where we reasonably believe someone is at serious risk, we may share limited information with the right authorities. If someone is in immediate danger, contact emergency services (in Kenya, 999 or 112) first."),
        )),
        LegalSection(20, "Children", listOf(
            c("20.1", "Who UniVibe is for.", "UniVibe is for university students and is not designed for children. You need a university email address to register."),
            c("20.2", "If a child joins.", "We do not knowingly keep the personal data of a child (a person under 18) unless the law’s requirements, such as consent from a parent or guardian, are met. If you think an account belongs to a child, or you are a parent or guardian with a concern, email ${LegalContact.EMAIL} and we will look into it promptly."),
        )),
        LegalSection(21, "If something goes wrong", listOf(
            c("21.1", "Our response.", "If personal data is lost, accessed or shared without permission, we will investigate, take steps to limit the harm and fix the cause."),
            c("21.2", "Telling you.", "Where the law requires us to tell you or a regulator, we will do so within the time it requires, and explain what happened and what you can do."),
            c("21.3", "Reporting an incident.", "Email ${LegalContact.EMAIL} if you suspect a privacy or security incident. Do not include passwords or login tokens."),
        )),
        LegalSection(22, "Third-party services and links", listOf(
            c("22.1", "Their own policies.", "The providers in Section 13.2 have their own terms and privacy practices, and the Android system, your phone maker and Google Play may process data under theirs."),
            c("22.2", "Links.", "If UniVibe links to another website or app, we do not control it. Read its privacy policy before you share anything."),
        )),
        LegalSection(23, "Changes to this Policy", listOf(
            c("23.1", "Updates.", "We may update this Policy when UniVibe, our providers or the law change. The version and date are shown at the top."),
            c("23.2", "Notice.", "For material changes we will tell you in the app, or by email where required, before they take effect where reasonably possible."),
            c("23.3", "Consent.", "If a change needs your consent, we will ask for it before the new use starts."),
        )),
        LegalSection(24, "Contact", listOf(
            c("24.1", "", "Operator: ${LegalContact.OPERATOR}"),
            c("24.2", "", "Contact person: ${LegalContact.PERSON}"),
            c("24.3", "", "Email (privacy requests, complaints and security reports): ${LegalContact.EMAIL}"),
            c("24.4", "", "Phone: ${LegalContact.PHONE}"),
        )),
        LegalSection(25, "Your privacy in short", listOf(
            b("We collect what is needed to run UniVibe: your account, profile, posts, messages, activity and a phone token for notifications."),
            b("We do not sell your data, show ads, or use analytics or advertising trackers."),
            b("We do not collect your location, contacts, camera, payments or ID documents. The microphone is used only to record voice messages you choose to send."),
            b("Messages are stored on our servers and are not end-to-end encrypted."),
            b("Your posts are visible to signed-in students in the audience you choose, never to the public web."),
            b("You can delete your account any time in Settings → Account → Delete account."),
            b("To ask about your data, email ${LegalContact.EMAIL}."),
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
