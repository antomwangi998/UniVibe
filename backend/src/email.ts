export interface Mailer {
  sendVerification(to: string, name: string, code: string): Promise<void>;
  sendPasswordReset(to: string, name: string, code: string): Promise<void>;
}

const escapeHtml = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function render(name: string, intro: string, code: string) {
  const first = name.split(' ')[0] || 'there';
  const text = `Hi ${first},\n\n${intro}\n\n${code}\n\nThe code expires in 10 minutes. If you didn't ask for it, you can ignore this email.\n\nUniVibe`;
  const html =
    `<p>Hi ${escapeHtml(first)},</p><p>${escapeHtml(intro)}</p>` +
    `<p style="font-size:28px;letter-spacing:6px;font-weight:700">${code}</p>` +
    `<p>The code expires in 10 minutes. If you didn't ask for it, you can ignore this email.</p><p>UniVibe</p>`;
  return { text, html };
}

/** Sends through Resend's HTTP API. Needs a verified sending domain to reach addresses other than your own. */
export function createResendMailer(apiKey: string, from: string): Mailer {
  async function send(to: string, subject: string, intro: string, code: string, name: string): Promise<void> {
    const { text, html } = render(name, intro, code);
    const response = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ from, to: [to], subject, text, html }),
    });
    if (!response.ok) throw new Error(`Resend responded with status ${response.status}`);
  }
  return {
    sendVerification: (to, name, code) =>
      send(to, 'Your UniVibe verification code', 'Use this code to verify your email address:', code, name),
    sendPasswordReset: (to, name, code) =>
      send(to, 'Reset your UniVibe password', 'Use this code to reset your password:', code, name),
  };
}

/** Development only: prints the code instead of emailing it. Never used in production. */
export function createConsoleMailer(): Mailer {
  return {
    sendVerification: async (to, _name, code) => console.log(`[dev email] verification code for ${to}: ${code}`),
    sendPasswordReset: async (to, _name, code) => console.log(`[dev email] reset code for ${to}: ${code}`),
  };
}
