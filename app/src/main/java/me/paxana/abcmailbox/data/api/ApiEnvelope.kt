package me.paxana.abcmailbox.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every JSON response from the API is `{ data, info, success, status, name }`.
 * Lists add `total`, `page`, `page_size`. Validation failures replace `data`
 * with `errors: [...]`, and controller-level 4xx responses may add `error`.
 * One type covers all of them so a single parser handles success and failure.
 */
@Serializable
data class ApiEnvelope<T>(
  val data: T? = null,
  val info: String? = null,
  val success: Boolean = true,
  val status: Int = 0,
  val name: String? = null,
  val errors: List<String>? = null,
  val error: String? = null,
  val total: Int? = null,
  val page: Int? = null,
  @SerialName("page_size") val pageSize: Int? = null,
  /** Only on the notification feed: how many entries the account has not read. */
  val unread: Int? = null,
)

/** A list response with its paging fields, as repositories hand it to Paging. */
data class Page<T>(val items: List<T>, val total: Int, val page: Int, val pageSize: Int) {
  val hasMore: Boolean get() = page * pageSize < total

  /** `copy` cannot change a type parameter, so mapping DTOs to domain models needs this. */
  fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), total, page, pageSize)
}

fun <T> ApiEnvelope<List<T>>.toPage(): Page<T> = Page(
  items = data.orEmpty(),
  total = total ?: data?.size ?: 0,
  page = page ?: 1,
  pageSize = pageSize ?: data?.size ?: 0,
)
