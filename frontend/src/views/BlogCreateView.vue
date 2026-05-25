<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { AlertCircle, ImagePlus, Loader2, Send } from '@lucide/vue';
import { publishBlog, uploadBlogImage } from '../api/citycopilot';
import { assetUrl } from '../utils/assets';

const router = useRouter();
const MAX_IMAGES = 6;
const MAX_IMAGE_SIZE = 5 * 1024 * 1024;

const form = reactive({
  shopId: '',
  title: '',
  content: '',
});
const images = ref<string[]>([]);
const uploading = ref(false);
const publishing = ref(false);
const errorMessage = ref('');
const imageWarning = ref('');

const canPublish = computed(
  () => form.title.trim().length >= 2 && form.content.trim().length >= 6 && images.value.length > 0 && !publishing.value,
);

function validateFile(file: File) {
  if (!file.type.startsWith('image/')) {
    return '只能上传图片文件';
  }
  if (file.size > MAX_IMAGE_SIZE) {
    return '单张图片不能超过 5MB';
  }
  return '';
}

async function handleFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const selectedFiles = Array.from(input.files || []);
  if (!selectedFiles.length) {
    return;
  }
  errorMessage.value = '';
  imageWarning.value = '';
  uploading.value = true;
  try {
    for (const file of selectedFiles) {
      if (images.value.length >= MAX_IMAGES) {
        errorMessage.value = `最多上传 ${MAX_IMAGES} 张图片`;
        break;
      }
      const fileError = validateFile(file);
      if (fileError) {
        errorMessage.value = fileError;
        continue;
      }
      const imagePath = await uploadBlogImage(file);
      images.value = images.value.concat(imagePath);
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '图片上传失败';
  } finally {
    uploading.value = false;
    input.value = '';
  }
}

function resolveShopId() {
  const value = form.shopId.trim();
  if (!value) {
    return undefined;
  }
  const shopId = Number(value);
  if (!Number.isInteger(shopId) || shopId <= 0) {
    throw new Error('店铺 ID 必须是正整数');
  }
  return shopId;
}

function markPreviewUnavailable() {
  imageWarning.value = '图片已上传，当前环境暂时不能预览原图';
}

async function handlePublish() {
  if (!canPublish.value) {
    errorMessage.value = images.value.length ? '请补全标题和正文' : '请至少上传 1 张图片';
    return;
  }
  errorMessage.value = '';
  publishing.value = true;
  try {
    const shopId = resolveShopId();
    const id = await publishBlog({
      shopId,
      title: form.title.trim(),
      content: form.content.trim(),
      images: images.value.join(','),
    });
    await router.replace({ name: 'blog-detail', params: { id } });
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '发布失败';
  } finally {
    publishing.value = false;
  }
}
</script>

<template>
  <section class="compose-layout">
    <div class="compose-intro">
      <p class="eyebrow">New note</p>
      <h1>写一篇新的探店笔记。</h1>
    </div>

    <form class="compose-panel" @submit.prevent="handlePublish">
      <label class="field">
        <span>标题</span>
        <input v-model.trim="form.title" maxlength="80" placeholder="例如：杭州这家茶餐厅值得专门来一趟" />
      </label>

      <label class="field">
        <span>关联店铺 ID</span>
        <input v-model.trim="form.shopId" inputmode="numeric" placeholder="可选" />
      </label>

      <label class="field">
        <span>正文</span>
        <textarea v-model.trim="form.content" rows="8" placeholder="写下环境、菜品、服务和真实感受" />
      </label>

      <label class="upload-drop">
        <input type="file" accept="image/*" multiple @change="handleFiles" />
        <ImagePlus :size="20" aria-hidden="true" />
        {{ uploading ? '上传中' : `添加图片 ${images.length}/${MAX_IMAGES}` }}
      </label>

      <div v-if="images.length" class="compose-images">
        <img v-for="image in images" :key="image" :src="assetUrl(image)" alt="已上传图片" @error="markPreviewUnavailable" />
      </div>

      <p v-if="imageWarning" class="form-hint">{{ imageWarning }}</p>

      <p v-if="errorMessage" class="form-error">
        <AlertCircle :size="17" aria-hidden="true" />
        {{ errorMessage }}
      </p>

      <button class="primary-action" type="submit" :disabled="!canPublish">
        <Loader2 v-if="publishing" class="spin" :size="17" aria-hidden="true" />
        <Send v-else :size="17" aria-hidden="true" />
        {{ publishing ? '发布中' : '发布' }}
      </button>
    </form>
  </section>
</template>
