<template>
  <section class="relative grid min-h-screen place-items-center bg-background px-8 pb-16 pt-8">
    <div class="grid w-[min(960px,100%)] overflow-hidden rounded-lg shadow-md md:grid-cols-[1.15fr_0.9fr]">
      <div class="flex flex-col justify-center bg-card p-10">
        <h1 class="m-0 text-2xl font-semibold text-foreground">进入管理后台工作台</h1>
        <p class="mt-3.5 max-w-xl text-[15px] leading-relaxed text-muted-foreground">
          这里用于管理文档接入、知识路由与对话观测。账号和密码由当前部署环境配置，登录后才能进入后台。
        </p>
      </div>

      <form class="flex flex-col justify-center border-l border-border bg-card p-10 max-md:border-l-0 max-md:border-t" @submit.prevent="submitLogin">
        <div>
          <h2 class="m-0 text-xl text-foreground">管理台登录</h2>
        </div>

        <div class="mt-5 flex flex-col gap-2">
          <Label for="login-username" class="text-muted-foreground">账号</Label>
          <Input
            id="login-username"
            v-model="form.username"
            type="text"
            placeholder="请输入后台账号"
            autocomplete="username"
          />
        </div>

        <div class="mt-5 flex flex-col gap-2">
          <Label for="login-password" class="text-muted-foreground">密码</Label>
          <Input
            id="login-password"
            v-model="form.password"
            type="password"
            placeholder="请输入后台密码"
            autocomplete="current-password"
          />
        </div>

        <p v-if="errorMessage" class="mt-4 text-sm text-destructive">{{ errorMessage }}</p>

        <div class="mt-6 flex gap-3">
          <Button class="flex-1" variant="outline" type="button" @click="goBackChat">返回聊天</Button>
          <Button class="flex-1" type="submit" :disabled="submitting">
            {{ submitting ? '登录中...' : '进入管理台' }}
          </Button>
        </div>
      </form>
    </div>

    <IcpFooter class="absolute inset-x-8 bottom-[22px] max-md:inset-x-[18px] max-md:bottom-[18px]" />
  </section>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import IcpFooter from '../components/IcpFooter.vue'
import { adminAuthApi, APIError } from '../api/api'
import { saveAdminAuth } from '../utils/adminAuth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const router = useRouter()
const route = useRoute()

const form = reactive({
  username: 'admin',
  password: 'admin123456'
})
const errorMessage = ref('')
const submitting = ref(false)

async function submitLogin() {
  errorMessage.value = ''
  if (!form.username.trim() || !form.password.trim()) {
    errorMessage.value = '请输入账号和密码。'
    return
  }

  submitting.value = true
  try {
    const result = await adminAuthApi.login({
      username: form.username.trim(),
      password: form.password
    })
    saveAdminAuth({
      username: result?.username || form.username.trim(),
      token: result?.token || ''
    })
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/admin')
      ? route.query.redirect
      : '/admin/dashboard'
    router.replace(redirect)
  } catch (error) {
    errorMessage.value = error instanceof APIError || error instanceof Error
      ? error.message
      : '登录失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

function goBackChat() {
  router.push('/chat')
}
</script>
