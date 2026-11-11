-- AlterTable
ALTER TABLE "Chat" ADD COLUMN     "parentChatId" TEXT,
ADD COLUMN     "participantIds" TEXT[] DEFAULT ARRAY[]::TEXT[],
ADD COLUMN     "persona" VARCHAR(1000) NOT NULL DEFAULT '',
ADD COLUMN     "responseStyle" TEXT NOT NULL DEFAULT 'balanced',
ADD COLUMN     "scenario" VARCHAR(2000) NOT NULL DEFAULT '',
ADD COLUMN     "summaryThrough" INTEGER NOT NULL DEFAULT 0,
ADD COLUMN     "tone" TEXT NOT NULL DEFAULT 'friendly';

-- AlterTable
ALTER TABLE "Message" ADD COLUMN     "replyToRequestId" TEXT,
ADD COLUMN     "sequence" SERIAL NOT NULL;

-- AlterTable
ALTER TABLE "Memory" ADD COLUMN     "pinned" BOOLEAN NOT NULL DEFAULT false;

-- CreateTable
CREATE TABLE "ChatRequest" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "chatId" TEXT NOT NULL,
    "content" VARCHAR(4000) NOT NULL,
    "fingerprint" TEXT NOT NULL,
    "tier" TEXT NOT NULL,
    "priceTokens" INTEGER NOT NULL DEFAULT 0,
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "errorCode" TEXT,
    "startedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "ChatRequest_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "AiProviderState" (
    "key" TEXT NOT NULL,
    "minuteAt" TIMESTAMP(3) NOT NULL,
    "dayAt" TIMESTAMP(3) NOT NULL,
    "minuteRequests" INTEGER NOT NULL DEFAULT 0,
    "dayRequests" INTEGER NOT NULL DEFAULT 0,
    "minuteTokens" INTEGER NOT NULL DEFAULT 0,
    "dayTokens" INTEGER NOT NULL DEFAULT 0,
    "costMicros" INTEGER NOT NULL DEFAULT 0,
    "failures" INTEGER NOT NULL DEFAULT 0,
    "retryAt" TIMESTAMP(3),

    CONSTRAINT "AiProviderState_pkey" PRIMARY KEY ("key")
);

-- CreateTable
CREATE TABLE "AiAttempt" (
    "id" TEXT NOT NULL,
    "requestId" TEXT,
    "provider" TEXT NOT NULL,
    "model" TEXT NOT NULL,
    "tier" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "errorCode" TEXT,
    "estimatedTokens" INTEGER NOT NULL,
    "costMicros" INTEGER NOT NULL,
    "latencyMs" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "AiAttempt_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "ChatRequest_userId_status_startedAt_idx" ON "ChatRequest"("userId", "status", "startedAt");

-- CreateIndex
CREATE INDEX "AiAttempt_provider_status_expiresAt_idx" ON "AiAttempt"("provider", "status", "expiresAt");

-- CreateIndex
CREATE INDEX "AiAttempt_requestId_idx" ON "AiAttempt"("requestId");

-- CreateIndex
CREATE UNIQUE INDEX "Message_sequence_key" ON "Message"("sequence");

-- CreateIndex
CREATE UNIQUE INDEX "Message_replyToRequestId_key" ON "Message"("replyToRequestId");

-- AddForeignKey
ALTER TABLE "ChatRequest" ADD CONSTRAINT "ChatRequest_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ChatRequest" ADD CONSTRAINT "ChatRequest_chatId_fkey" FOREIGN KEY ("chatId") REFERENCES "Chat"("id") ON DELETE CASCADE ON UPDATE CASCADE;

