{% macro normalize_status(value, fallback='unknown') %}
    coalesce(lower({{ value }}), '{{ fallback }}')
{% endmacro %}

select {{ normalize_status('status') }} as normalized_status
from customers
