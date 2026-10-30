#!/bin/bash
# 秘密情報 (API トークンなど) の混入を調べる。値は絶対に表示せず、場所 (ファイル:行) だけを表示する。
#   bash tools/check-secrets.sh --staged     コミット前 (pre-commit フックから呼ばれる)
#   bash tools/check-secrets.sh --worktree   作業ツリー (追跡中 + 未追跡で ignore されていないもの)
#   bash tools/check-secrets.sh --history    全ブランチの全履歴
cd "$(git rev-parse --show-toplevel)" || exit 2
MODE="${1:---staged}"
SELF="tools/check-secrets.sh"

# 既知の形式のトークン (Modrinth の PAT は mrp_ で始まる想定、GitHub のトークン、AWS キー、秘密鍵)
KNOWN='mrp_[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----'
# 名前つきの代入 (token / api key / secret / password に 16 文字以上の値)
ASSIGN='(token|api[_-]?key|secret|passw(or)?d)[A-Za-z0-9_]*[[:space:]]*[:=][[:space:]]*.?[A-Za-z0-9_./+=-]{16,}'
# 名前つき + UUID 形式の値
NAMEUUID='(token|api[_-]?key|secret).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
# 値を「読み出しているだけ」の行は除外
EXCL='providers\.|getenv|gradleProperty|environmentVariable|secrets\.'
ALL="$KNOWN|$ASSIGN|$NAMEUUID"

hits=0
show() { while IFS= read -r l; do [ -n "$l" ] && { echo "  $l"; hits=$((hits+1)); }; done; }

case "$MODE" in
  --staged)
    while IFS= read -r f; do
      [ "$f" = "$SELF" ] && continue
      case "$f" in
        .env|.env.*|*/.env|*/.env.*|*.token|*.pem|*.p12|*.pfx|*.jks|*.keystore|*id_rsa*|*id_ed25519*)
          echo "  $f  (秘密情報になりやすいファイル名)"; hits=$((hits+1)); continue ;;
      esac
      lines=$(git show ":$f" 2>/dev/null | grep -IinE "$ALL" | grep -viE "$EXCL" | cut -d: -f1 | tr '\n' ' ')
      if [ -n "$lines" ]; then echo "  $f  行: $lines"; hits=$((hits+1)); fi
    done < <(git diff --cached --name-only --diff-filter=ACM)
    ;;
  --worktree)
    show < <(git grep -IinE "$ALL" --untracked -- . ":(exclude)$SELF" 2>/dev/null | grep -viE "$EXCL" | cut -d: -f1,2)
    ;;
  --history)
    show < <(git grep -IinE "$ALL" $(git rev-list --all) -- . ":(exclude)$SELF" 2>/dev/null | grep -viE "$EXCL" | cut -d: -f1-3 | sed -E 's/^([0-9a-f]{8})[0-9a-f]*:/\1:/')
    ;;
  *) echo "usage: $0 --staged|--worktree|--history"; exit 2 ;;
esac

if [ "$hits" -gt 0 ]; then
  echo "秘密情報らしき内容が $hits 件あります (値は表示していません)。"
  echo "  - コミット前なら: その行を消し、トークンは ~/.gradle/gradle.properties だけに置く"
  echo "  - 履歴や push 済みなら: そのトークンを直ちに失効させて作り直す (履歴の書き換えだけでは不十分)"
  echo "  - 誤検出が疑わしいときは、行番号だけを伝えて相談する (値は貼らない)"
  exit 1
fi
echo "OK: 秘密情報らしき内容は見つかりませんでした ($MODE)"
