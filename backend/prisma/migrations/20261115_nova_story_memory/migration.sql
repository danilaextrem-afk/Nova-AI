-- Group speakers, automatic memories, per-user character memory and character scenarios.
ALTER TABLE "Message" ADD COLUMN "speakerId" TEXT;
ALTER TABLE "Message" ADD COLUMN "turnRequestId" TEXT;
CREATE INDEX "Message_turnRequestId_idx" ON "Message"("turnRequestId");
ALTER TABLE "Memory" ADD COLUMN "source" TEXT NOT NULL DEFAULT 'user';
ALTER TABLE "Chat" ADD COLUMN "memoryThrough" INTEGER NOT NULL DEFAULT 0;

CREATE TABLE "CharacterMemory" (
  "id" TEXT NOT NULL,
  "userId" TEXT NOT NULL,
  "characterId" TEXT NOT NULL,
  "content" VARCHAR(1000) NOT NULL,
  "pinned" BOOLEAN NOT NULL DEFAULT false,
  "source" TEXT NOT NULL DEFAULT 'user',
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "CharacterMemory_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "CharacterMemory_userId_characterId_idx" ON "CharacterMemory"("userId", "characterId");
ALTER TABLE "CharacterMemory" ADD CONSTRAINT "CharacterMemory_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "CharacterMemory" ADD CONSTRAINT "CharacterMemory_characterId_fkey" FOREIGN KEY ("characterId") REFERENCES "Character"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "CharacterScenario" (
  "id" TEXT NOT NULL,
  "characterId" TEXT NOT NULL,
  "title" VARCHAR(80) NOT NULL,
  "scenario" VARCHAR(2000) NOT NULL,
  "greeting" VARCHAR(1000) NOT NULL DEFAULT '',
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "CharacterScenario_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "CharacterScenario_characterId_idx" ON "CharacterScenario"("characterId");
ALTER TABLE "CharacterScenario" ADD CONSTRAINT "CharacterScenario_characterId_fkey" FOREIGN KEY ("characterId") REFERENCES "Character"("id") ON DELETE CASCADE ON UPDATE CASCADE;
