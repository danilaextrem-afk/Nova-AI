-- Smart character creator: explicit adult age, gender, trait chips, uploaded avatars.
ALTER TABLE "Character" ADD COLUMN "age" INTEGER;
ALTER TABLE "Character" ADD COLUMN "gender" TEXT;
ALTER TABLE "Character" ADD COLUMN "traits" TEXT[] DEFAULT ARRAY[]::TEXT[];
ALTER TABLE "Character" ADD CONSTRAINT "Character_age_adult" CHECK ("age" IS NULL OR "age" >= 18);

CREATE TABLE "Media" (
  "id" TEXT NOT NULL,
  "ownerId" TEXT NOT NULL,
  "mime" TEXT NOT NULL,
  "data" BYTEA NOT NULL,
  "bytes" INTEGER NOT NULL,
  "source" TEXT NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "Media_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "Media_ownerId_createdAt_idx" ON "Media"("ownerId", "createdAt");
ALTER TABLE "Media" ADD CONSTRAINT "Media_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
