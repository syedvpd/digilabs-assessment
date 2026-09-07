import type { Metadata } from 'next';
import { Inter } from 'next/font/google';
import type { ReactNode } from 'react';
import './globals.css';
import { Analytics } from '@/components/Analytics';

const inter = Inter({ subsets: ['latin'], variable: '--font-inter', display: 'swap' });

export const metadata: Metadata = {
  title: 'Digilabss — Demand, designed to compound.',
  description: 'A premium performance marketing story for Tier 1 markets.',
  metadataBase: new URL('https://example.com'),
  openGraph: { title: 'Digilabss — Demand, designed to compound.', description: 'Performance marketing without the agency clutter.', type: 'website' },
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return <html lang="en"><body className={inter.variable}><Analytics />{children}</body></html>;
}
