import test from 'node:test'
import assert from 'node:assert/strict'
import { galleryImageUrl } from '../src/utils/galleryImageUrl.ts'

test('legacy local warehouse images follow the current API origin', () => {
  for (const host of ['localhost:8080', '127.0.0.1:8090', '[::1]:8080']) {
    assert.equal(galleryImageUrl(`http://${host}/api/files/public/1/product%20photo.jpg?size=480`),
      '/api/files/public/1/product%20photo.jpg?size=480')
  }
})

test('external storage URLs and unrelated local endpoints remain unchanged', () => {
  for (const value of [undefined, '/api/files/space/1/product.jpg', 'https://bucket.example.com/product.jpg',
    'https://localhost.example.com/api/files/product.jpg', 'http://localhost:8080/api/other/product.jpg']) {
    assert.equal(galleryImageUrl(value), value)
  }
})
