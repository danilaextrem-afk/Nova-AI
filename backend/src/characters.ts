import type { FastifyInstance } from 'fastify';
import type { PrismaClient, Prisma } from '@prisma/client';
import { z } from 'zod';

/**
 * All user-supplied characters are private until explicitly submitted for review.
 * Publishing never makes a character public without moderation approval.
 */
const characterInput = z.object({
  name: z.string().trim().min(1).max(80),
  greeting: z.string().trim().min(1).max(1000),
  category: z.string().trim().min(1).max(50),
  tags: z.array(z.string().trim().min(1).max(40)).max(10).default([]),
  language: z.string().trim().min(2).max(12).default('ru'),
  appearance: z.string().max(1000).nullable().optional(),
  biography: z.string().max(3000).nullable().optional(),
  personality: z.string().max(1500).nullable().optional(),
  interests: z.string().max(500).nullable().optional(),
  backstory: z.string().max(3000).nullable().optional(),
  speechStyle: z.string().max(500).nullable().optional(),
  scenario: z.string().max(2000).nullable().optional(),
  avatarUrl: z.string().url().startsWith('https://').max(2048).nullable().optional(),
  // Adult audience (18+): romance and mature drama between adult characters, never sexually explicit.
  mature: z.boolean().default(false),
}).strict();

const updateInput = characterInput.partial().refine(
  value => Object.keys(value).length > 0,
  { message: 'At least one field must be supplied' },
);
const uuidParam = z.object({ id: z.string().uuid() });
const pageQuery = z.object({
  limit: z.coerce.number().int().min(1).max(50).default(25),
  cursor: z.string().uuid().optional(),
});
const publicSelection = {
  id: true, name: true, appearance: true, biography: true, personality: true,
  greeting: true, category: true, tags: true, language: true, avatarUrl: true, mature: true,
  createdAt: true, publication:{select:{id:true,likesCount:true}}, ownerId:true, owner: { select: { profile: { select: { displayName: true } } } },
} satisfies Prisma.CharacterSelect;

/** Mature (18+) public characters are visible only to users who opted in after confirming 18+. */
export async function showsMature(db: PrismaClient, userId: string) {
  return !!(await db.profile.findUnique({ where: { userId }, select: { showMature: true } }))?.showMature;
}
const visibleTo = (userId: string, showMature = false): Prisma.CharacterWhereInput => ({
  OR: [
    { ownerId: userId },
    { isHidden: false, publication: { is: { status: 'PUBLISHED' } }, owner:{blocksGot:{none:{blockerId:userId}},blocksMade:{none:{blockedId:userId}}},
      ...(showMature ? {} : { mature: false }) },
  ],
});

export async function registerCharacterRoutes(app: FastifyInstance, db: PrismaClient) {
  const protectedRoute = { preHandler: app.auth };

  app.post('/v1/characters', protectedRoute, async (req: any, reply) => {
    const parsed = characterInput.safeParse(req.body);
    if (!parsed.success) return reply.code(400).send({ error: 'INVALID_CHARACTER' });
    const character = await db.character.create({
      data: { ...parsed.data, ownerId: req.user.sub },
    });
    return reply.code(201).send({ character });
  });

  app.get('/v1/characters/mine', protectedRoute, async (req: any, reply) => {
    const query = pageQuery.safeParse(req.query);
    if (!query.success) return reply.code(400).send({ error: 'INVALID_QUERY' });
    const characters = await db.character.findMany({
      where: { ownerId: req.user.sub },
      orderBy: { id: 'asc' },
      take: query.data.limit,
      ...(query.data.cursor ? { cursor: { id: query.data.cursor }, skip: 1 } : {}),
    });
    return {
      items: characters,
      nextCursor: characters.length === query.data.limit ? characters.at(-1)?.id : null,
    };
  });

  // Discover searches exclusively through approved public characters.
  // Optional text, category and tag filters are combined using AND.
  app.get('/v1/characters/catalog', protectedRoute, async (req: any, reply) => {
    const query = pageQuery.extend({
      q: z.string().trim().max(80).optional(),
      category: z.string().trim().max(50).optional(),
      tag: z.string().trim().max(40).optional(),
      sort: z.enum(['recent', 'name', 'popular']).default('recent'),
      // General and 18+ characters are listed in separate sections, never mixed.
      section: z.enum(['general', 'mature']).default('general'),
    }).safeParse(req.query);
    if (!query.success) return reply.code(400).send({ error: 'INVALID_QUERY' });
    const { q, category, tag, sort, cursor, limit, section } = query.data;
    if (section === 'mature' && !await showsMature(db, req.user.sub)) return reply.code(403).send({ error: 'MATURE_SECTION_DISABLED' });
    const where: Prisma.CharacterWhereInput = {
      isHidden: false, mature: section === 'mature',
      owner:{blocksGot:{none:{blockerId:req.user.sub}},blocksMade:{none:{blockedId:req.user.sub}}},
      publication: { is: { status: 'PUBLISHED' } },
      ...(category ? { category: { equals: category, mode: 'insensitive' } } : {}),
      ...(tag ? { tags: { has: tag } } : {}),
      ...(q ? {
        OR: [
          { name: { contains: q, mode: 'insensitive' } },
          { biography: { contains: q, mode: 'insensitive' } },
          { personality: { contains: q, mode: 'insensitive' } },
          { greeting: { contains: q, mode: 'insensitive' } },
        ],
      } : {}),
    };
    const characters = await db.character.findMany({
      where,
      select: publicSelection,
      orderBy: sort === 'popular' ? [{publication:{likesCount:'desc'}},{id:'asc'}] : sort === 'name' ? [{ name: 'asc' }, { id: 'asc' }]
        : [{ createdAt: 'desc' }, { id: 'desc' }],
      take: limit,
      ...(cursor ? { cursor: { id: cursor }, skip: 1 } : {}),
    });
    return {
      items: characters,
      nextCursor: characters.length === limit ? characters.at(-1)?.id : null,
    };
  });

  app.get('/v1/characters/favorites', protectedRoute, async (req: any, reply) => {
    const query = pageQuery.safeParse(req.query);
    if (!query.success) return reply.code(400).send({ error: 'INVALID_QUERY' });
    const favorites = await db.favorite.findMany({
      where: { userId: req.user.sub, character: visibleTo(req.user.sub, await showsMature(db, req.user.sub)) },
      select: { character: { select: publicSelection }, createdAt: true },
      orderBy: { characterId: 'asc' },
      take: query.data.limit,
      ...(query.data.cursor ? {
        cursor: { userId_characterId: { userId: req.user.sub, characterId: query.data.cursor } },
        skip: 1,
      } : {}),
    });
    return {
      items: favorites,
      nextCursor: favorites.length === query.data.limit
        ? favorites.at(-1)?.character.id : null,
    };
  });

  app.get('/v1/characters/:id', protectedRoute, async (req: any, reply) => {
    const params = uuidParam.safeParse(req.params);
    if (!params.success) return reply.code(400).send({ error: 'INVALID_ID' });
    const character = await db.character.findFirst({
      where: { id: params.data.id, ...visibleTo(req.user.sub, await showsMature(db, req.user.sub)) },
    });
    if (!character) return reply.code(404).send({ error: 'NOT_FOUND' });
    return { character };
  });

  app.patch('/v1/characters/:id', protectedRoute, async (req: any, reply) => {
    const params = uuidParam.safeParse(req.params);
    const body = updateInput.safeParse(req.body);
    if (!params.success || !body.success) return reply.code(400).send({ error: 'INVALID_INPUT' });
    const count = await db.$transaction(async tx => {
      const changed = await tx.character.updateMany({
        where: { id: params.data.id, ownerId: req.user.sub, isLibrary: false },
        data: body.data,
      });
      if (changed.count) {
        // A publicly approved character requires fresh moderation after any edit.
        await tx.publication.updateMany({
          where: { characterId: params.data.id },
          data: { status: 'PENDING_REVIEW', publishedAt: null },
        });
      }
      return changed.count;
    });
    if (!count) return reply.code(404).send({ error: 'NOT_FOUND' });
    return { updated: true, publicationStatus: 'PENDING_REVIEW' };
  });

  app.delete('/v1/characters/:id', protectedRoute, async (req: any, reply) => {
    const params = uuidParam.safeParse(req.params);
    if (!params.success) return reply.code(400).send({ error: 'INVALID_ID' });
    const result = await db.character.deleteMany({
      where: { id: params.data.id, ownerId: req.user.sub, isLibrary: false },
    });
    if (!result.count) return reply.code(404).send({ error: 'NOT_FOUND' });
    return reply.code(204).send();
  });

  app.post('/v1/characters/:id/clone', protectedRoute, async (req: any, reply) => {
    const params = uuidParam.safeParse(req.params);
    if (!params.success) return reply.code(400).send({ error: 'INVALID_ID' });
    const source = await db.character.findFirst({
      where: { id: params.data.id, ...visibleTo(req.user.sub, await showsMature(db, req.user.sub)) },
    });
    if (!source) return reply.code(404).send({ error: 'NOT_FOUND' });
    const character = await db.character.create({
      data: {
        ownerId: req.user.sub, name: source.name, greeting: source.greeting,
        category: source.category, language: source.language, tags: source.tags,
        appearance: source.appearance, biography: source.biography,
        personality: source.personality, interests: source.interests,
        backstory: source.backstory, speechStyle: source.speechStyle,
        scenario: source.scenario, avatarUrl: source.avatarUrl,
        isHidden: true, isLibrary: false,
      },
    });
    return reply.code(201).send({ character });
  });

  app.post('/v1/characters/:id/publish', protectedRoute, async (req: any, reply) => {
    const params = uuidParam.safeParse(req.params);
    if (!params.success) return reply.code(400).send({ error: 'INVALID_ID' });
    const owned = await db.character.findFirst({
      where: { id: params.data.id, ownerId: req.user.sub, isLibrary: false },
      select: { id: true, isHidden: true },
    });
    if (!owned) return reply.code(404).send({ error: 'NOT_FOUND' });
    await db.character.update({where:{id:owned.id},data:{isHidden:false}});
    await db.publication.upsert({
      where: { characterId: owned.id },
      create: { characterId: owned.id, status: 'PENDING_REVIEW' },
      update: { status: 'PENDING_REVIEW', publishedAt: null },
    });
    return { publicationStatus: 'PENDING_REVIEW' };
  });

  app.post('/v1/characters/:id/hide', protectedRoute, async (req: any, reply) => {
    const params = uuidParam.safeParse(req.params);
    if (!params.success) return reply.code(400).send({ error: 'INVALID_ID' });
    const count = await db.$transaction(async tx => {
      const changed = await tx.character.updateMany({
        where: { id: params.data.id, ownerId: req.user.sub, isLibrary: false },
        data: { isHidden: true },
      });
      if (changed.count) await tx.publication.updateMany({
        where: { characterId: params.data.id },
        data: { status: 'HIDDEN', publishedAt: null },
      });
      return changed.count;
    });
    if (!count) return reply.code(404).send({ error: 'NOT_FOUND' });
    return { hidden: true };
  });

  app.put('/v1/characters/:id/favorite', protectedRoute, async (req: any, reply) => {
    const params = uuidParam.safeParse(req.params);
    if (!params.success) return reply.code(400).send({ error: 'INVALID_ID' });
    const character = await db.character.findFirst({
      where: { id: params.data.id, ...visibleTo(req.user.sub, await showsMature(db, req.user.sub)) }, select: { id: true },
    });
    if (!character) return reply.code(404).send({ error: 'NOT_FOUND' });
    await db.favorite.upsert({
      where: { userId_characterId: { userId: req.user.sub, characterId: character.id } },
      create: { userId: req.user.sub, characterId: character.id },
      update: {},
    });
    return { favorite: true };
  });

  app.delete('/v1/characters/:id/favorite', protectedRoute, async (req: any, reply) => {
    const params = uuidParam.safeParse(req.params);
    if (!params.success) return reply.code(400).send({ error: 'INVALID_ID' });
    await db.favorite.deleteMany({
      where: { userId: req.user.sub, characterId: params.data.id },
    });
    return { favorite: false };
  });
}
