package sh.repost.client.kotlin

/** Marks nested Repost Kotlin builder scopes. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
public annotation class RepostDsl
