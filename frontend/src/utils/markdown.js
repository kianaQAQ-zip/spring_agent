import MarkdownIt from 'markdown-it'
import { Document, Packer, Paragraph, HeadingLevel } from 'docx'
import * as XLSX from 'xlsx'

const md = new MarkdownIt({ breaks: true, linkify: true, html: false })

/** 渲染 Markdown 为 HTML（禁用原始 html 防 XSS） */
export function renderMarkdown(text) {
  return md.render(text || '')
}

/** 解析 [n] 引用为文本段 + 引用段 */
export function parseCitationSegments(content) {
  const segs = []
  const re = /(\[\d+\])/g
  let last = 0
  let m
  while ((m = re.exec(content)) !== null) {
    if (m.index > last) segs.push({ type: 'text', text: content.slice(last, m.index) })
    segs.push({ type: 'cite', index: parseInt(m[1].slice(1, -1), 10) })
    last = m.index + m[0].length
  }
  if (last < content.length) segs.push({ type: 'text', text: content.slice(last) })
  return segs
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function stamp() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_${pad(d.getHours())}-${pad(d.getMinutes())}`
}

/** 导出为 Markdown */
export function exportAsMarkdown(content) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  downloadBlob(blob, `AI回答_${stamp()}.md`)
}

/** 导出为 Word */
export async function exportAsWord(content) {
  const lines = content.split('\n')
  const children = []
  for (const line of lines) {
    if (line.startsWith('### ')) {
      children.push(new Paragraph({ text: line.replace('### ', ''), heading: HeadingLevel.HEADING_3, spacing: { before: 12, after: 6 } }))
    } else if (line.startsWith('## ')) {
      children.push(new Paragraph({ text: line.replace('## ', ''), heading: HeadingLevel.HEADING_2, spacing: { before: 14, after: 8 } }))
    } else if (line.startsWith('# ')) {
      children.push(new Paragraph({ text: line.replace('# ', ''), heading: HeadingLevel.HEADING_1, spacing: { before: 16, after: 10 } }))
    } else if (line.trim().startsWith('- ') || line.trim().startsWith('1. ')) {
      children.push(new Paragraph({ text: `  ${line.trim()}`, spacing: { after: 2 } }))
    } else if (line.trim() === '') {
      children.push(new Paragraph({ text: '', spacing: { after: 4 } }))
    } else {
      children.push(new Paragraph({ text: line.trim(), spacing: { after: 2 } }))
    }
  }
  const doc = new Document({
    sections: [{
      properties: {},
      children: children.length > 0 ? children : [new Paragraph({ text: content })]
    }]
  })
  const blob = await Packer.toBlob(doc)
  downloadBlob(blob, `AI回答_${stamp()}.docx`)
}

/** 检测内容是否含 Markdown 表格 */
function hasTable(content) {
  const lines = content.trim().split('\n').filter((l) => l.trim())
  const tableLines = lines.filter((l) => /^\|.*\|$/.test(l.trim()))
  return tableLines.length >= 2 && tableLines.length >= lines.length * 0.7
}

function parseMarkdownTable(content) {
  const lines = content.trim().split('\n').filter((l) => l.trim().startsWith('|'))
  const data = []
  for (const line of lines) {
    if (/^\|[\s\-:|]+\|$/.test(line.trim())) continue
    const cells = line.trim().split('|').filter((c) => c.trim()).map((c) => c.trim())
    if (cells.length > 0) data.push(cells)
  }
  return data
}

/** 导出为 Excel（优先解析 Markdown 表格） */
export function exportAsExcel(content) {
  let data = parseMarkdownTable(content)
  if (data.length === 0) {
    data = content.split('\n').filter((l) => l.trim()).map((l) => [l.trim()])
  }
  const ws = XLSX.utils.aoa_to_sheet(data)
  if (data.length > 0) {
    ws['!cols'] = data[0].map(() => ({ wch: 20 }))
  }
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, 'Sheet1')
  XLSX.writeFile(wb, `AI回答_${stamp()}.xlsx`)
}

export { hasTable }
