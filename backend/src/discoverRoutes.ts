import type { FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';
import { z } from 'zod';
import { visibleCharacter } from './chatRoutes.js';
import { showsMature } from './characters.js';

/**
 * Discovery and creator pages. Only published, non-hidden characters of non-blocked authors ever
 * appear here; private characters and private chat content are never read for other users.
 */
const card = { id: true, name: true, biography: true, greeting: true, category: true, tags: true, avatarUrl: true, mature: true,
  createdAt: true, ownerId: true, publication: { select: { id: true, likesCount: true } } } as const;
const esc = (s: string) => s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));

export async function registerDiscoverRoutes(app: FastifyInstance, db: PrismaClient) {
  const auth = { preHandler: app.auth };
  const publicFor = async (userId: string) => ({ AND: [visibleCharacter(userId, await showsMature(db, userId)),
    { isHidden: false, publication: { is: { status: 'PUBLISHED' as const } } }] });

  // Personal feed: what matches the genres and tags of the user's favourites and stories, then popular and new.
  app.get('/v1/discover/recommended', auth, async (req: any, reply) => {
    const q = z.object({ limit: z.coerce.number().int().min(1).max(40).default(20) }).safeParse(req.query);
    if (!q.success) return reply.code(400).send({ error: 'INVALID_QUERY' });
    const userId: string = req.user.sub;
    const [favorites, chats] = await Promise.all([
      db.favorite.findMany({ where: { userId }, select: { character: { select: { id: true, category: true, tags: true } } }, take: 50 }),
      db.chat.findMany({ where: { userId }, select: { character: { select: { id: true, category: true, tags: true } } }, orderBy: { updatedAt: 'desc' }, take: 50 }),
    ]);
    const liked = [...favorites, ...chats].map(x => x.character);
    const known = new Set(liked.map(c => c.id));
    const categories = new Map<string, number>(); const tags = new Map<string, number>();
    for (const c of liked) {
      categories.set(c.category.toLowerCase(), (categories.get(c.category.toLowerCase()) ?? 0) + 1);
      for (const t of c.tags) tags.set(t.toLowerCase(), (tags.get(t.toLowerCase()) ?? 0) + 1);
    }
    const pool = await db.character.findMany({ where: { ...(await publicFor(userId)), ownerId: { not: userId } }, select: card,
      orderBy: [{ publication: { likesCount: 'desc' } }, { createdAt: 'desc' }], take: 300 });
    const week = Date.now() - 7 * 86400000;
    const scored = pool.filter(c => !known.has(c.id)).map(c => {
      const taste = 3 * Math.min(3, categories.get(c.category.toLowerCase()) ?? 0) + 2 * c.tags.filter(t => tags.has(t.toLowerCase())).length;
      const fresh = c.createdAt.getTime() > week;
      const score = taste + Math.log2(1 + (c.publication?.likesCount ?? 0)) + (fresh ? 1.5 : 0);
      return { ...c, score, reason: taste > 0 ? 'TASTE' : fresh ? 'NEW' : 'POPULAR' };
    }).sort((a, b) => b.score - a.score || b.createdAt.getTime() - a.createdAt.getTime());
    return { items: scored.slice(0, q.data.limit).map(({ score, ...c }) => c), personalised: liked.length > 0 };
  });

  // Themed shelves: new, popular, and one shelf per genre that has published characters.
  app.get('/v1/discover/collections', auth, async (req: any) => {
    const where = await publicFor(req.user.sub);
    const [fresh, popular, all] = await Promise.all([
      db.character.findMany({ where, select: card, orderBy: { createdAt: 'desc' }, take: 8 }),
      db.character.findMany({ where, select: card, orderBy: [{ publication: { likesCount: 'desc' } }, { createdAt: 'desc' }], take: 8 }),
      db.character.findMany({ where, select: card, orderBy: [{ publication: { likesCount: 'desc' } }, { createdAt: 'desc' }], take: 300 }),
    ]);
    const byGenre = new Map<string, typeof all>();
    for (const c of all) { const list = byGenre.get(c.category) ?? []; if (list.length < 8) list.push(c); byGenre.set(c.category, list); }
    return { collections: [{ key: 'new', title: 'NEW', items: fresh }, { key: 'popular', title: 'POPULAR', items: popular },
      ...Array.from(byGenre, ([genre, items]) => ({ key: 'genre:' + genre, title: genre, items })).sort((a, b) => b.items.length - a.items.length).slice(0, 8)] };
  });

  // Public author page: only what the author published.
  app.get('/v1/creators/:id', auth, async (req: any, reply) => {
    if (!z.string().uuid().safeParse(req.params.id).success) return reply.code(400).send({ error: 'INVALID_ID' });
    const me: string = req.user.sub;
    const blocked = await db.userBlock.count({ where: { OR: [{ blockerId: me, blockedId: req.params.id }, { blockerId: req.params.id, blockedId: me }] } });
    const author = blocked ? null : await db.user.findFirst({ where: { id: req.params.id, status: 'ACTIVE' }, select: { id: true, createdAt: true, profile: { select: { displayName: true, bio: true, avatarUrl: true } } } });
    if (!author) return reply.code(404).send({ error: 'CREATOR_NOT_FOUND' });
    const [characters, followers, following] = await Promise.all([
      db.character.findMany({ where: { ...(await publicFor(me)), ownerId: author.id }, select: card, orderBy: [{ publication: { likesCount: 'desc' } }, { createdAt: 'desc' }], take: 60 }),
      db.creatorFollow.count({ where: { creatorId: author.id } }),
      db.creatorFollow.count({ where: { creatorId: author.id, followerId: me } }),
    ]);
    return { id: author.id, displayName: author.profile?.displayName ?? 'Автор', bio: author.profile?.bio ?? null, since: author.createdAt,
      followers, isFollowing: following > 0, likes: characters.reduce((n, c) => n + (c.publication?.likesCount ?? 0), 0), characters };
  });

  // Author analytics: counts only — never the text of other people's chats.
  app.get('/v1/me/creator-stats', auth, async (req: any) => {
    const me: string = req.user.sub;
    const mine = await db.character.findMany({ where: { ownerId: me }, select: { id: true, name: true, isHidden: true,
      publication: { select: { id: true, status: true, likesCount: true } } }, orderBy: { createdAt: 'desc' }, take: 100 });
    const ids = mine.map(c => c.id);
    const [chats, favorites, comments, followers] = await Promise.all([
      db.chat.groupBy({ by: ['characterId'], where: { characterId: { in: ids }, userId: { not: me } }, _count: { _all: true } }),
      db.favorite.groupBy({ by: ['characterId'], where: { characterId: { in: ids } }, _count: { _all: true } }),
      db.socialComment.groupBy({ by: ['publicationId'], where: { publicationId: { in: mine.map(c => c.publication?.id).filter((x): x is string => !!x) }, status: 'PUBLISHED' }, _count: { _all: true } }),
      db.creatorFollow.count({ where: { creatorId: me } }),
    ]);
    const count = (rows: { _count: { _all: number } }[], key: string, id: string | undefined) => (rows as any[]).find(r => r[key] === id)?._count._all ?? 0;
    const items = mine.map(c => ({ id: c.id, name: c.name, status: c.isHidden ? 'HIDDEN' : c.publication?.status ?? 'DRAFT',
      likes: c.publication?.likesCount ?? 0, stories: count(chats, 'characterId', c.id), favorites: count(favorites, 'characterId', c.id),
      comments: count(comments, 'publicationId', c.publication?.id) }));
    return { followers, totals: { characters: items.length, likes: items.reduce((n, i) => n + i.likes, 0), stories: items.reduce((n, i) => n + i.stories, 0),
      favorites: items.reduce((n, i) => n + i.favorites, 0) }, items };
  });

  // Public share page (no account needed): published, all-ages characters only; everything is HTML-escaped.
  app.get('/c/:id', { config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async (req: any, reply) => {
    const c = z.string().uuid().safeParse(req.params.id).success ? await db.character.findFirst({ where: { id: req.params.id, isHidden: false, mature: false,
      publication: { is: { status: 'PUBLISHED' } }, owner: { status: 'ACTIVE' } }, select: { name: true, biography: true, category: true } }) : null;
    reply.header('Content-Type', 'text/html; charset=utf-8').header('Cache-Control', 'public, max-age=300');
    if (!c) return reply.code(404).send('<!doctype html><meta charset="utf-8"><title>NOVA AI</title><p>Персонаж не найден или скрыт автором.</p>');
    const apk = 'https://github.com/danilaextrem-afk/Nova-AI/releases/download/nova-preview/NOVA-AI-FINAL.apk';
    return `<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(c.name)} — NOVA AI</title><meta property="og:title" content="${esc(c.name)} — NOVA AI"><meta property="og:description" content="${esc((c.biography || '').slice(0, 160))}">
<body style="margin:0;background:#0c0b14;color:#eee;font-family:system-ui,sans-serif"><main style="max-width:520px;margin:0 auto;padding:32px 20px">
<p style="color:#f3b3cf;letter-spacing:.2em;font-size:12px">NOVA AI · ${esc(c.category.toUpperCase())}</p><h1>${esc(c.name)}</h1>
<p style="color:#bbb4c9;line-height:1.5">${esc((c.biography || '').slice(0, 600))}</p>
<p style="color:#bbb4c9;font-size:13px">Вымышленный AI-персонаж. Открой эту ссылку на телефоне с установленным приложением NOVA AI, чтобы начать историю.</p>
<p><a href="${apk}" style="display:inline-block;background:#c3a6ff;color:#1b1430;padding:14px 22px;border-radius:28px;text-decoration:none;font-weight:600">Скачать NOVA AI для Android</a></p>
</main></body></html>`;
  });
}
