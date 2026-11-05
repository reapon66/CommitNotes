<script setup>
import Icon from './Icon.vue'
defineProps({ modelValue: { type: String, default: '' }, label: { type: String, default: 'Cor' } })
const emit = defineEmits(['update:modelValue'])
const COLORS = [['#64748b', 'Ardósia'], ['#3b82f6', 'Azul'], ['#6366f1', 'Índigo'], ['#8b5cf6', 'Violeta'], ['#ec4899', 'Rosa'], ['#ef4444', 'Vermelho'],
  ['#f97316', 'Laranja'], ['#f59e0b', 'Âmbar'], ['#eab308', 'Amarelo'], ['#22c55e', 'Verde'], ['#14b8a6', 'Turquesa'], ['#0ea5e9', 'Céu']]
</script>

<template>
  <div class="swatches" role="radiogroup" :aria-label="label">
    <button v-for="[color, name] in COLORS" :key="color" type="button" class="swatch" role="radio" :aria-checked="modelValue === color" :aria-label="name" :title="name"
      :style="{ '--chip': color }" @click="emit('update:modelValue', color)">
      <Icon v-if="modelValue === color" name="check" :size="14" :stroke="3" />
    </button>
  </div>
</template>
