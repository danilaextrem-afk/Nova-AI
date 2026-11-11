-- Regenerated assistant replies keep earlier versions; regeneration requests remember their target.
ALTER TABLE "Message" ADD COLUMN "alternatives" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];
ALTER TABLE "Message" ADD COLUMN "regeneratedByRequestId" TEXT;
CREATE UNIQUE INDEX "Message_regeneratedByRequestId_key" ON "Message"("regeneratedByRequestId");
ALTER TABLE "ChatRequest" ADD COLUMN "targetMessageId" TEXT;
