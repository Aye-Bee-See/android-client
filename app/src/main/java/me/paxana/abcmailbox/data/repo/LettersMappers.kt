package me.paxana.abcmailbox.data.repo

import me.paxana.abcmailbox.data.api.ReturnNoteField
import me.paxana.abcmailbox.data.api.AttachmentDto
import me.paxana.abcmailbox.data.api.ChatDto
import me.paxana.abcmailbox.data.api.MessageDto
import me.paxana.abcmailbox.data.api.StatusHistoryDto
import me.paxana.abcmailbox.domain.Attachment
import me.paxana.abcmailbox.domain.LastMessage
import me.paxana.abcmailbox.domain.Letter
import me.paxana.abcmailbox.domain.ReplyReference
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.Resent
import me.paxana.abcmailbox.domain.HeldReason
import me.paxana.abcmailbox.domain.ReturnReason
import me.paxana.abcmailbox.domain.StatusChange
import me.paxana.abcmailbox.domain.Thread

fun AttachmentDto.toDomain() = Attachment(id = id, messageId = message, name = originalName, mimeType = mimeType, size = size, nonce = nonce)

fun StatusHistoryDto.toDomain() = StatusChange(
  from = fromStatus?.let { LetterStatus.fromKey(it) },
  to = LetterStatus.fromKey(toStatus),
  at = createdAt.toInstantOrNull(),
  byUserId = changedBy,
  reason = ReturnReason.fromKey(reason),
  note = note,
)

fun MessageDto.toDomain(): Letter = Letter(
  id = id,
  threadId = chat,
  prisonerId = prisoner,
  writerId = user,
  fromPrisoner = sender == "prisoner",
  status = LetterStatus.fromKey(status),
  // In e2e mode messageText is null and the body arrives as ciphertext; phase 5 decrypts here.
  body = messageText.orEmpty(),
  relayNote = relayNote?.takeIf { it.isNotBlank() },
  relayGroupId = relayChapter ?: relayGroup?.id,
  relayGroupName = relayGroup?.name,
  keep = keep,
  createdAt = createdAt.toInstantOrNull(),
  statusChangedAt = statusChangedAt.toInstantOrNull(),
  history = statusHistory.orEmpty().map { it.toDomain() },
  attachments = attachments.orEmpty().map { it.toDomain() },
  returnReason = ReturnReason.fromKey(returnReason),
  heldReason = HeldReason.fromKey(heldReason),
  resendOfId = resendOf,
  resentAs = resentAs.orEmpty().map { Resent(it.id, LetterStatus.fromKey(it.status), it.createdAt.toInstantOrNull()) },
  returnNoteOnLetter = returnNote.takeIf { it != ReturnNoteField.ABSENT && it != ReturnNoteField.NONE },
  returnNoteKnown = returnNote != ReturnNoteField.ABSENT,
  // The API says the nine digits bare on the message; they are shown as they are printed, four, four and the check digit.
  replyReference = replyReference?.takeIf { it.isNotBlank() }?.let(ReplyReference::pretty),
  repliesToId = repliesTo,
  footer = footer?.let { me.paxana.abcmailbox.domain.LetterFooter(it.name, it.anonymous, it.careOf?.id, it.careOf?.name, it.reference?.let(ReplyReference::pretty), it.replySheetAllowed) },
)

/** `letter` and `preview` let the caller decrypt in end-to-end mode; the defaults are the server-mode pass-through. */
fun ChatDto.toDomain(
  letter: (MessageDto) -> Letter = { it.toDomain() },
  preview: (me.paxana.abcmailbox.data.api.LastMessageDto) -> String? = { it.messageText?.takeIf { t -> t.isNotBlank() } },
): Thread = Thread(
  id = id,
  prisonerId = prisoner,
  prisoner = prisonerDetails?.toDomain(),
  lastMessage = lastMessage?.let {
    LastMessage(
      id = it.id,
      fromPrisoner = it.sender == "prisoner",
      status = LetterStatus.fromKey(it.status),
      at = it.createdAt.toInstantOrNull(),
      preview = preview(it),
    )
  },
  lastActivity = lastMessageAt.toInstantOrNull() ?: updatedAt.toInstantOrNull(),
  // Oldest first for a conversation view; the API returns them in insertion order already.
  letters = messages.orEmpty().map(letter).sortedBy { it.createdAt },
  writer = userDetails?.let { u ->
    me.paxana.abcmailbox.domain.ThreadWriter(u.id, u.name?.takeIf { it.isNotBlank() } ?: u.username, u.managedBy, u.anonymousForChapter)
  },
  heldCount = heldCount,
  heldReasons = heldReasons.mapNotNull { HeldReason.fromKey(it) },
)
