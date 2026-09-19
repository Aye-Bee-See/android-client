# Translating ABC Mailbox for Android

The app ships in English, Spanish and Russian. The Spanish and Russian were written on 19 September 2026 by the same AI assistant that wrote the app, not by native speakers. **They need a review by people who speak the language and know the movement's vocabulary before a public release.** This page is for those reviewers, and for whoever adds the next language.

## Where the words are

| File | What |
| --- | --- |
| `app/src/main/res/values/strings.xml` | English: every sentence the app itself says, with notes for translators in XML comments |
| `app/src/main/res/values-es/strings.xml`, `values-ru/strings.xml` | The translations, same names |
| `app/src/main/res/values*/mail_rules.xml` | The 39 mail rules of the API's master list, as `rule_<tag>` and `rule_<tag>_desc` |

To review, open the language's two files beside the English ones and read them side by side. To change a sentence, change the text between the tags and nothing else. To add a language, copy `values-es` to `values-<code>` (for example `values-fr`), translate, and build: Android finds it by itself, and Android 13+ lists it under Settings, System, Languages, App languages. On older phones the app follows the phone's language.

## Three rules the tests enforce

`TranslationsTest` fails the build if a translation breaks one of these.

1. **Every string exists in every language.** Nothing falls back to English by accident.
2. **Placeholders are kept exactly.** `%1$s`, `%2$d` and the like are where the app puts a name, a date or a number. A translation may move them anywhere in the sentence, and must keep each one. `%1$s` and `%2$s` may swap places; that is what the numbers are for.
3. **Plurals have the forms the language needs.** English and Spanish: `one`, `other` (Spanish also carries `many`, identical to `other`, because Android asks for it). Russian: `one` (1, 21, 31…), `few` (2 to 4, 22 to 24…), `many` (0, 5 to 20, 25…), `other` (fractions). In Russian the `one` form must contain the number, since it also serves 21 and 101.

## What is deliberately not translated

- The app's name.
- **The name line of an envelope** (`name_with_number`, "John Smith #12345"). It is copied onto paper for a prison mailroom and follows the mailroom's conventions, not the language of the volunteer's phone.
- Anything that comes from the server: prisoners' names, biographies, interests, facility notes, a group's description.
- **The server's own sentences.** When the API refuses something it explains why, in English, and the app shows that explanation as it came, because it is more specific than anything the app could say. The app sends `Accept-Language` with every request, so this changes the day the API can answer in other languages.
- A mail rule that an admin added after the app's list was made. It reads in the server's English until the next app release adds `rule_<tag>` for it. (Regenerate the English list with the script in the comment at the top of `values/mail_rules.xml`.)
- The hidden developer server dialog.

## Glossary, as used on 19 September 2026

Consistency matters more than any single choice. If a reviewer changes a term, change it everywhere.

| English | Spanish | Russian | Note |
| --- | --- | --- | --- |
| prisoner | persona presa / personas presas | заключённый / заключённые | Spanish uses the gender-neutral "persona presa" throughout; adjectives agree with "persona" (feminine). Groups that write "presxs" or "pres@s" may prefer their own form: a style decision for the network |
| political prisoners | personas presas políticas | политзаключённые | |
| facility | centro (penitenciario) | учреждение | Short forms in labels |
| mail rules | normas de correspondencia | правила переписки | |
| routing | vía de envío | способ доставки | How mail physically reaches a facility |
| relay group | grupo de reenvío | группа пересылки | The group that prints and posts letters inside the country |
| collecting group | grupo de recogida | группа сбора | |
| support group | grupo de apoyo | группа поддержки | |
| partner group | grupo asociado | партнёрская группа | |
| writer | remitente | автор (писем) | "Remitente" avoids a gendered "escritor/a". Russian "автор" is masculine by grammar, as usual |
| anonymous writer | remitente anónimo | анонимный автор | |
| letter | carta | письмо | Spanish participles agree with "carta" (enviada, impresa) |
| inbox (tab) | Buzón | Письма | |
| directory (tab) | Directorio | Каталог | |
| claim token | código de activación | код активации | Not a literal "claim". What matters is that a person reading it aloud understands it is a one-time code |
| to claim an account | activar la cuenta | активировать аккаунт | |
| hand off (an account) | entregar la cuenta | передать аккаунт | |
| recovery code | código de recuperación | код восстановления | |
| encryption key | clave de cifrado | ключ шифрования | |
| group key | clave del grupo | ключ группы | |
| to seal (a key to someone) | sellar | запечатать | |
| to hand the key (to a member) | entregar la clave | передать ключ | |
| end-to-end encrypted | cifrado de extremo a extremo | сквозное шифрование | |
| queued / printed / mailed / received | en cola / impresa / enviada por correo / recibida | в очереди / напечатано / отправлено почтой / получено | |
| commissary | economato | тюремный магазин | |
| outbox: "waiting to be sent" | pendientes de envío | ждут отправки | |
| sign in / sign out | iniciar sesión / cerrar sesión | войти / выйти | |

Tone: plain and warm, addressing the reader as "tú" in Spanish and "вы" in Russian. No exclamation marks. Where Russian would need a gendered past tense for the user ("I have saved this code"), the text uses "сохранил(а)".
