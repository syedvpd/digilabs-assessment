'use client';

import { FormEvent, useState } from 'react';
import { ArrowUpRight, Check, Loader2 } from 'lucide-react';
import { trackLead } from './Analytics';

const initial = { name: '', email: '', company: '', budget: '' };

export function LeadForm() {
  const [form, setForm] = useState(initial);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');

  const update = (key: keyof typeof initial, value: string) => {
    setForm(prev => ({ ...prev, [key]: value }));
    setErrors(prev => ({ ...prev, [key]: '' }));
  };

  const validate = () => {
    const next: Record<string, string> = {};
    if (!form.name.trim()) next.name = 'Please enter your name.';
    if (!/^\S+@\S+\.\S+$/.test(form.email)) next.email = 'Use a valid work email.';
    if (!form.company.trim()) next.company = 'Please enter your company.';
    if (!form.budget) next.budget = 'Choose a range.';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setStatus('loading');
    try {
      const res = await fetch('/api/leads', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form) });
      if (!res.ok) throw new Error('capture failed');
      localStorage.setItem('digilabss_last_lead', JSON.stringify({ ...form, capturedAt: new Date().toISOString() }));
      trackLead({ budget: form.budget });
      setStatus('success');
      setForm(initial);
    } catch {
      setStatus('error');
    }
  };

  if (status === 'success') {
    return <div className="lead-card success-card"><div><div className="success-mark"><Check size={21} /></div><p className="eyebrow">Request received</p><h3>Good. Now we have something to build from.</h3><p>Your demo lead was captured by the local CRM endpoint and stored locally for verification.</p></div><button onClick={() => setStatus('idle')} className="text-button">Send another <ArrowUpRight size={16} /></button></div>;
  }

  return (
    <form onSubmit={submit} noValidate className="lead-card">
      <div className="form-heading"><p className="eyebrow">Start the conversation</p><h3>Bring the problem.<br />We’ll bring the point of view.</h3></div>
      <div className="field-grid">
        {([['name','Name','Your name'],['email','Work email','you@company.com'],['company','Company','Company name']] as const).map(([key, label, placeholder]) => (
          <label key={key} className={key === 'company' ? 'field full' : 'field'}>
            <span>{label}</span><input value={form[key]} onChange={e => update(key, e.target.value)} placeholder={placeholder} aria-invalid={Boolean(errors[key])} />{errors[key] && <small>{errors[key]}</small>}
          </label>
        ))}
        <label className="field full"><span>Monthly media budget</span><select value={form.budget} onChange={e => update('budget', e.target.value)} aria-invalid={Boolean(errors.budget)}><option value="">Select a range</option><option>$5k–$15k</option><option>$15k–$50k</option><option>$50k–$100k</option><option>$100k+</option></select>{errors.budget && <small>{errors.budget}</small>}</label>
      </div>
      <button disabled={status === 'loading'} className="form-submit">{status === 'loading' ? <><Loader2 size={17} className="spin" /> Capturing</> : <>Book the working session <ArrowUpRight size={17} /></>}</button>
      {status === 'error' && <p className="form-error">Something went wrong. Please try again.</p>}
      <p className="form-note">Demo endpoint only. Replace with the production CRM or Sheet integration at handoff.</p>
    </form>
  );
}
