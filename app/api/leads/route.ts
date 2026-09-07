import { NextResponse } from 'next/server';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

type Lead = { id: string; name: string; email: string; company: string; budget: string; receivedAt: string };
const memoryStore: Lead[] = [];

export async function GET() {
  return NextResponse.json({ ok: true, mode: 'demo-memory', count: memoryStore.length, leads: memoryStore.slice(-20).reverse() });
}

export async function POST(request: Request) {
  try {
    const body = await request.json();
    const name = String(body.name ?? '').trim();
    const email = String(body.email ?? '').trim();
    const company = String(body.company ?? '').trim();
    const budget = String(body.budget ?? '').trim();

    if (!name || !EMAIL_RE.test(email) || !company || !budget) {
      return NextResponse.json({ ok: false, message: 'Please provide valid lead details.' }, { status: 400 });
    }

    const lead: Lead = { id: `lead_${Date.now()}`, name, email, company, budget, receivedAt: new Date().toISOString() };
    memoryStore.push(lead);
    if (memoryStore.length > 50) memoryStore.shift();

    // Verifiable local/demo CRM store: visible in the server terminal and via GET /api/leads.
    console.log('[Digilabss DEMO CRM] lead received', lead);

    // Optional production handoff: set CRM_WEBHOOK_URL to a Google Apps Script / Make / Zapier / CRM webhook.
    const webhook = process.env.CRM_WEBHOOK_URL;
    if (webhook) {
      try {
        await fetch(webhook, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(lead),
          cache: 'no-store',
        });
      } catch (error) {
        console.warn('[Digilabss CRM webhook] delivery failed; demo capture still succeeded.', error);
      }
    }

    return NextResponse.json({ ok: true, message: 'Lead captured.', lead, verification: '/api/leads' });
  } catch {
    return NextResponse.json({ ok: false, message: 'Invalid request.' }, { status: 400 });
  }
}
