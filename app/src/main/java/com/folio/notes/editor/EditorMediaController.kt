package com.folio.notes.editor

import com.folio.notes.app.FolioRuntime
import com.folio.notes.model.PageImage
import com.folio.notes.model.page
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Editor commands over the shared document engine; no independent notebook content. */
internal class EditorMediaController(private val runtime: FolioRuntime) {
    internal val _imageFiles = MutableStateFlow(0)

    fun images(pageId: String, images: List<PageImage>) {
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            // Pictures cannot be changed on a page whose own content has not been read yet.
            if (!page.loaded || page.images === images || page.images == images) return
            replacePage(page.copy(images = images))
        }
    }

    fun addImage(image: PageImage, bytes: ByteArray? = null, pageId: String? = runtime._state.value.page?.id) {
        with(runtime) {
            val state = _state.value
            val note = state.notes.find { note -> note.pages.any { it.id == pageId } } ?: return
            val page = note.pages.page(pageId) ?: return
            if (!page.loaded) return
            commitEdit(note, page, page.copy(images = page.images + image.copy(layer = activeLayerOf(page)))) {
                if (bytes != null) {
                    repository.saveImage(note.id, image.id, bytes)
                    // The page already shows the picture; tell the editor its file has now landed.
                    _imageFiles.update { it + 1 }
                }
            }
        }
    }

    fun updateImage(image: PageImage) {
        with(runtime) {
            val page = _state.value.page ?: return
            images(page.id, page.images.map { if (it.id == image.id) image else it })
        }
    }

    fun removeImage(id: String) {
        with(runtime) {
            val page = _state.value.page ?: return
            images(page.id, page.images.filterNot { it.id == id })
        }
    }

    fun bringImageToFront(id: String) {
        with(runtime) {
            val page = _state.value.page ?: return
            val target = page.images.find { it.id == id } ?: return
            images(page.id, page.images.filterNot { it.id == id } + target)
        }
    }

    fun sendImageToBack(id: String) {
        with(runtime) {
            val page = _state.value.page ?: return
            val target = page.images.find { it.id == id } ?: return
            images(page.id, listOf(target) + page.images.filterNot { it.id == id })
        }
    }

    fun rotateImageClockwise(id: String) {
        with(runtime) {
            val page = _state.value.page ?: return
            val target = page.images.find { it.id == id } ?: return
            updateImage(target.rotatedClockwise())
        }
    }

    fun rotateImageCounterClockwise(id: String) {
        with(runtime) {
            val page = _state.value.page ?: return
            val target = page.images.find { it.id == id } ?: return
            updateImage(target.rotatedCounterClockwise())
        }
    }

    fun cropImage(id: String, left: Float, top: Float, right: Float, bottom: Float) {
        with(runtime) {
            val page = _state.value.page ?: return
            val target = page.images.find { it.id == id } ?: return
            val cropped = target.withCrop(left, top, right, bottom)
            if (cropped == target) return
            updateImage(cropped)
        }
    }

    fun resetImageCrop(id: String) {
        with(runtime) {
            val page = _state.value.page ?: return
            val target = page.images.find { it.id == id } ?: return
            if (!target.isCropped()) return
            updateImage(target.withResetCrop())
        }
    }
}
