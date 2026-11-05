/**
 * @name 代理的配置
 * @see 在生产环境 代理是无法生效的，所以这里没有生产环境的配置
 * @doc https://umijs.org/docs/guides/proxy
 *
 * 代理到本地跑起来的 yeweiyi-cloud（cmd/server 默认监听 :8080，见该仓库
 * config.example.yaml）。test/pre 的真实地址待运维环境定案后再填。
 */
export default {
  dev: {
    '/api/': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    },
  },
  test: {
    '/api/': {
      target: 'your test url',
      changeOrigin: true,
    },
  },
  pre: {
    '/api/': {
      target: 'your pre url',
      changeOrigin: true,
    },
  },
};
