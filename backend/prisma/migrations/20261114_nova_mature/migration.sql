-- 18+ (Mature) section: per-character label and per-user opt-in after explicit confirmation.
ALTER TABLE "Character" ADD COLUMN "mature" BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX "Character_mature_idx" ON "Character"("mature");
ALTER TABLE "Profile" ADD COLUMN "showMature" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Profile" ADD COLUMN "matureConfirmedAt" TIMESTAMP(3);
