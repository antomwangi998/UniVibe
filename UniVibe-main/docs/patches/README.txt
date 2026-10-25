UniVibe - patch 2: Terms of Service readable in the app (apply AFTER patch 1)

 - Sign-up "Terms of Service" link now opens the real Terms (scrollable sheet).
 - Settings > Help & support > Terms of Service (full page, deep link univibe://settings/help/terms), searchable.
 - Settings > About > Terms of Service opens the same text.
 - Help "Report a problem" now mails info@univibe.swiftpesanet.co.ke (was a placeholder address).
 - Contact details live in features/legal/TermsOfService.kt (LegalContact) - change them there when you get a domain.

Apply: copy files/ over the project, or  cd UniVibe-main && patch -p0 < univibe-terms-in-app.diff
Verify: ./gradlew testDebugUnitTest   (not run in the authoring sandbox: no network)
