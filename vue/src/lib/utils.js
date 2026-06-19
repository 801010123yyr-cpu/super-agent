import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

// 合并 Tailwind 类名：clsx 处理条件类，twMerge 消解冲突的工具类（如 px-2 px-4）
export function cn(...inputs) {
  return twMerge(clsx(inputs))
}
