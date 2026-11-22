# Merged into `github`

同一产品两套 API：commit status 与 Checks。

```groovy
// 旧
runAtom(atom: 'github-checks', version: '1.latest', inputs: [...])
// 新（其余参数名不变）
runAtom(atom: 'github', version: '1.latest', inputs: [action: 'checks', ...])
```

本仓库只读保留，便于历史构建追溯。
