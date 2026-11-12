ALTER TABLE "SocialComment" ADD COLUMN "parentId" TEXT;

CREATE INDEX "SocialComment_parentId_idx" ON "SocialComment"("parentId");

ALTER TABLE "SocialComment" ADD CONSTRAINT "SocialComment_parentId_fkey"
  FOREIGN KEY ("parentId") REFERENCES "SocialComment"("id")
  ON DELETE SET NULL ON UPDATE CASCADE;
