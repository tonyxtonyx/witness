#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

GITLAB_URL="${WITNESS_GITLAB_URL:-http://localhost:8929}"
GITLAB_TOKEN="${WITNESS_GITLAB_TOKEN:-glpat-witness-demo-token-2026}"
GITLAB_PROJECT="root%2Fwitness"
WAIT_SECONDS="${WITNESS_GITLAB_WAIT_SECONDS:-900}"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.gitlab.yml)

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

echo "Starting GitLab CE. The first start downloads a large image and can take several minutes..."
"${COMPOSE[@]}" up -d gitlab

deadline=$((SECONDS + WAIT_SECONDS))
until curl --silent --show-error --fail "$GITLAB_URL/users/sign_in" >/dev/null 2>&1; do
  if ((SECONDS >= deadline)); then
    echo "GitLab did not become ready within ${WAIT_SECONDS} seconds." >&2
    echo "Inspect it with: docker compose -f docker-compose.yml -f docker-compose.gitlab.yml logs gitlab" >&2
    exit 1
  fi
  printf "."
  sleep 5
done
printf "\nGitLab is ready. Bootstrapping the local demo token and project...\n"

"${COMPOSE[@]}" exec -T -e WITNESS_GITLAB_TOKEN="$GITLAB_TOKEN" gitlab gitlab-rails runner '
    value = ENV.fetch("WITNESS_GITLAB_TOKEN")
    user = User.find_by_username!("root")
    name = "Witness demo API"
    existing = PersonalAccessToken.find_by_token(value)

    unless existing&.active?
      existing&.destroy!
      user.personal_access_tokens.where(name: name).destroy_all
      token = user.personal_access_tokens.create(
        scopes: ["api"],
        name: name,
        expires_at: 364.days.from_now
      )
      token.set_token(value)
      token.save!
    end
  '

project_status="$(
  curl --silent --output "$temporary_directory/project.json" --write-out "%{http_code}" --header "PRIVATE-TOKEN: $GITLAB_TOKEN" "$GITLAB_URL/api/v4/projects/$GITLAB_PROJECT"
)"

if [[ "$project_status" == "404" ]]; then
  curl --silent --show-error --fail --request POST --header "PRIVATE-TOKEN: $GITLAB_TOKEN" --data-urlencode "name=Witness" --data-urlencode "path=witness" --data-urlencode "visibility=private" --data-urlencode "initialize_with_readme=true" --data-urlencode "default_branch=main" "$GITLAB_URL/api/v4/projects" >"$temporary_directory/project.json"
elif [[ "$project_status" != "200" ]]; then
  echo "GitLab project lookup failed with HTTP $project_status." >&2
  cat "$temporary_directory/project.json" >&2
  exit 1
fi

until curl --silent --show-error --fail --header "PRIVATE-TOKEN: $GITLAB_TOKEN" "$GITLAB_URL/api/v4/projects/$GITLAB_PROJECT/repository/branches/main" >/dev/null 2>&1; do
  if ((SECONDS >= deadline)); then
    echo "The GitLab project did not initialize its main branch in time." >&2
    exit 1
  fi
  sleep 2
done

while IFS= read -r model_file; do
  relative_path="${model_file#"$PROJECT_ROOT"/}"
  encoded_path="${relative_path//\//%2F}"
  file_status="$(
    curl --silent --output /dev/null --write-out "%{http_code}" --header "PRIVATE-TOKEN: $GITLAB_TOKEN" "$GITLAB_URL/api/v4/projects/$GITLAB_PROJECT/repository/files/$encoded_path?ref=main"
  )"

  if [[ "$file_status" == "404" ]]; then
    curl --silent --show-error --fail --request POST --header "PRIVATE-TOKEN: $GITLAB_TOKEN" --data-urlencode "branch=main" --data-urlencode "commit_message=chore(model): seed $relative_path" --data-urlencode "content@$model_file" "$GITLAB_URL/api/v4/projects/$GITLAB_PROJECT/repository/files/$encoded_path" >/dev/null
    echo "Seeded $relative_path"
  elif [[ "$file_status" != "200" ]]; then
    echo "GitLab file lookup failed for $relative_path with HTTP $file_status." >&2
    exit 1
  fi
done < <(
  find "$PROJECT_ROOT/semantic-model" -type f \( -name "*.yaml" -o -name "*.yml" \) -print | sort
)

echo "Starting Witness in GitLab-governed mode..."
"${COMPOSE[@]}" up --build -d demo-db trino backend frontend
"${COMPOSE[@]}" ps

echo
echo "Witness:  http://localhost:3000"
echo "GitLab:   http://localhost:8929/root/witness"
echo "Login:    root"
echo "Password: WitnessDemo123!"
echo
echo "Create a metric or object in Witness, open its Merge Request, merge it in GitLab,"
echo "and return to Witness. The active catalog follows main within about 10 seconds."
