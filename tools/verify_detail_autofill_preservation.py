#!/usr/bin/env python3
"""Compare private on-device row digests, never document values or original data."""
import collections

def verify(before, after):
    assert set(before['tables']) == set(after['tables']), 'Protected table set changed'
    additions = {}
    for table, old in before['tables'].items():
        new = after['tables'][table]
        assert all(new.get(key) == value for key, value in old.items()), 'Existing '+table+' row changed'
        additions[table] = set(new) - set(old)
        if table not in ('details', 'tags', 'document_tags'):
            assert not additions[table], 'Unexpected '+table+' addition'
    added = additions['details']
    assert added <= set(after['automatic_rows']), 'New detail lacks automatic saved provenance'
    owners = collections.Counter(after['automatic_rows'][key] for key in added)
    assert set(before['revisions']) == set(after['revisions']), 'Document set changed'
    assert all(after['revisions'][key] == value + owners[key] for key, value in before['revisions'].items()), 'Unexplained metadata revision change'
    assert all(after['automatic_relations'].get(key) in added for key in additions['document_tags']), 'New relation does not belong to new automatic detail'
    assert all(set(after['automatic_tags'].get(key, [])) & added for key in additions['tags']), 'New tag does not belong to new automatic detail'
    return {'existing_rows_exact': True, 'metadata_revisions_explained': True,
            'automatic_details_added': len(added), 'automatic_organization_tags_added': len(additions['tags']),
            'automatic_organization_relations_added': len(additions['document_tags']),
            'preexisting_rows_checked': {table: len(rows) for table, rows in before['tables'].items()}}
