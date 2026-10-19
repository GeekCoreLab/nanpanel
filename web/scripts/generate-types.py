"""Regenerate DTOs from the repository OpenAPI contract (requires PyYAML)."""
from pathlib import Path
import json
import yaml
root = Path(__file__).resolve().parents[2]
schemas = yaml.safe_load((root / 'api/openapi.yaml').read_text())['components']['schemas']
def ts(value):
    if '$ref' in value:
        return value['$ref'].split('/')[-1]
    if 'const' in value:
        return json.dumps(value['const'])
    if 'enum' in value:
        return ' | '.join(json.dumps(x) for x in value['enum'])
    if 'oneOf' in value:
        return ' | '.join(ts(x) for x in value['oneOf'])
    kind = value.get('type')
    if isinstance(kind, list):
        return ' | '.join(ts({**value, 'type': x}) for x in kind)
    if kind == 'object':
        return '{ ' + '; '.join(k + ('' if k in value.get('required', []) else '?') + ': ' + ts(v) for k, v in value.get('properties', {}).items()) + ' }'
    if kind == 'array':
        return '(' + ts(value['items']) + ')[]'
    return {'integer': 'number', 'number': 'number', 'boolean': 'boolean', 'null': 'null', 'string': 'string'}.get(kind, 'unknown')
output = '// Generated from api/openapi.yaml. Regenerate with scripts/generate-types.py.\n'
output += ''.join('export type ' + name + ' = ' + ts(schema) + ';\n' for name, schema in schemas.items())
(root / 'web/src/lib/types.ts').write_text(output)
