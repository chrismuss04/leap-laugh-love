import { APIRequestContext, expect } from '@playwright/test';

/**
 * Mailpit, the mail catcher every service sends to (see docker-compose.yml). Registration only
 * says how an application went by email, so the suite reads the applicant's inbox the way a
 * person would. This is the one address besides BASE_URL the suite needs.
 */
const MAILPIT_URL = (process.env.MAILPIT_URL ?? 'http://localhost:8025').replace(/\/$/, '');

export const CONFIRM_SUBJECT = 'Confirm your email to open your Leapfolio account';
export const APPLICATION_SUBJECT = 'Your Leapfolio account application';
export const DETAILS_REUSED_SUBJECT = 'Someone applied for a Leapfolio account with your details';

/** The text of the newest email to `to` with this subject. Emails are sent after the response, so it waits. */
export async function emailTo(request: APIRequestContext, to: string, subject: string): Promise<string> {
  let text: string | undefined;
  await expect
    .poll(
      async () => {
        const search = await request
          .get(`${MAILPIT_URL}/api/v1/search`, { params: { query: `to:"${to}" subject:"${subject}"` } })
          .catch(error => {
            throw new Error(`Mailpit is not answering at ${MAILPIT_URL} (set MAILPIT_URL). ${error}`);
          });
        const newest = (await search.json()).messages?.[0];
        if (!newest) {
          return false;
        }
        text = (await (await request.get(`${MAILPIT_URL}/api/v1/message/${newest.ID}`)).json()).Text;
        return true;
      },
      { message: `an email "${subject}" to ${to} (looked in Mailpit at ${MAILPIT_URL})`, timeout: 30_000 }
    )
    .toBe(true);
  return text!;
}

/** The token in the confirmation link emailed to a new applicant. */
export async function confirmationToken(request: APIRequestContext, email: string): Promise<string> {
  const text = await emailTo(request, email, CONFIRM_SUBJECT);
  const token = /\/verify-email\?token=([\w-]+)/.exec(text)?.[1];
  if (!token) {
    throw new Error(`The confirmation email to ${email} has no link:\n${text}`);
  }
  return token;
}
