package com.github.adamantcheese.chan.core.loader.impl

import com.github.adamantcheese.chan.core.cache.CacheHandler
import com.github.adamantcheese.chan.core.cache.FileCacheListener
import com.github.adamantcheese.chan.core.cache.FileCacheV2
import com.github.adamantcheese.chan.core.loader.LoaderResult
import com.github.adamantcheese.chan.core.loader.LoaderType
import com.github.adamantcheese.chan.core.loader.OnDemandContentLoader
import com.github.adamantcheese.chan.core.loader.PostLoaderData
import com.github.adamantcheese.chan.core.manager.PrefetchIndicatorAnimationManager
import com.github.adamantcheese.chan.core.model.Post
import com.github.adamantcheese.chan.core.model.PostImage
import com.github.adamantcheese.chan.core.model.orm.Loadable
import com.github.adamantcheese.chan.utils.BackgroundUtils
import com.github.k1rakishou.fsaf.file.AbstractFile
import com.github.k1rakishou.fsaf.file.RawFile
import io.reactivex.Scheduler
import io.reactivex.Single

class PrefetchLoader(
        private val scheduler: Scheduler,
        private val fileCacheV2: FileCacheV2,
        private val cacheHandler: CacheHandler,
        private val prefetchIndicatorAnimationManager: PrefetchIndicatorAnimationManager
) : OnDemandContentLoader(LoaderType.PrefetchLoader) {

    override fun isCached(postLoaderData: PostLoaderData): Single<Boolean> {
        return Single.fromCallable {
                    return@fromCallable postLoaderData.post.postImages
                            .filter { postImage -> postImage.canBeUsedForPrefetch() }
                            .all { postImage ->
                                val fileUrl = postImage.imageUrl?.toString()
                                        ?: return@all true

                                return@all cacheHandler.isAlreadyDownloaded(fileUrl)
                            }
                }
                .subscribeOn(scheduler)
                .onErrorReturnItem(false)
    }

    override fun startLoading(postLoaderData: PostLoaderData): Single<LoaderResult> {
        BackgroundUtils.ensureBackgroundThread()

        val post = postLoaderData.post
        val loadable = postLoaderData.loadable

        val prefetchList = tryGetPrefetchBatch(loadable, post)
        if (prefetchList.isEmpty()) {
            postLoaderData.post.postImages.forEach { postImage -> onPrefetchEnded(postImage, true) }
            return rejected()
        }

        prefetchList.forEach { prefetch ->
            val cancelableDownload = fileCacheV2.enqueueMediaPrefetchRequest(
                    prefetch.loadable,
                    prefetch.postImage
            )

            if (cancelableDownload == null) {
                // Already cached or something like that
                onPrefetchEnded(prefetch.postImage)
                return@forEach
            }

            cancelableDownload.addCallback(object : FileCacheListener() {
                override fun onSuccess(file: RawFile?) {
                    postLoaderData.post.setContentLoadedForLoader(loaderType)
                    onPrefetchEnded(prefetch.postImage)
                }

                override fun onFail(exception: Exception?) = onPrefetchEnded(prefetch.postImage)
                override fun onNotFound() = onPrefetchEnded(prefetch.postImage)
                override fun onStop(file: AbstractFile?) = onPrefetchEnded(prefetch.postImage)
                override fun onCancel() = onPrefetchEnded(prefetch.postImage, false)
            })
            postLoaderData.addDisposeFunc { cancelableDownload.cancelPrefetch() }
        }

        // Always false for prefetches because there is nothing in the view that we need to update
        // after doing a prefetch
        return succeeded(false)
    }

    override fun cancelLoading(postLoaderData: PostLoaderData) {
        BackgroundUtils.ensureMainThread()

        return postLoaderData.disposeAll()
    }

    private fun tryGetPrefetchBatch(
            loadable: Loadable,
            post: Post
    ): List<Prefetch> {
        if (post.isContentLoadedForLoader(loaderType)) {
            return emptyList()
        }

        if (!loadable.isSuitableForPrefetch) {
            return emptyList()
        }

        return getPrefetchBatch(post, loadable)
    }

    private fun getPrefetchBatch(post: Post, loadable: Loadable): List<Prefetch> {
        BackgroundUtils.ensureBackgroundThread()

        return post.postImages.mapNotNull { postImage ->
            if (!postImage.canBeUsedForPrefetch()) {
                return@mapNotNull null
            }

            return@mapNotNull Prefetch(postImage, loadable)
        }
    }

    private fun onPrefetchEnded(postImage: PostImage, success: Boolean = true) {
        if (success) {
            postImage.setPrefetched()
        }

        prefetchIndicatorAnimationManager.onPrefetchDone(postImage)
    }

    private data class Prefetch(
            val postImage: PostImage,
            val loadable: Loadable
    )

    companion object {
        private const val TAG = "PrefetchLoader"
    }
}
