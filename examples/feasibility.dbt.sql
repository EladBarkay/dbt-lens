-- Relation hole: removing the Jinja expression leaves an incomplete FROM clause.
select customer_id from {{ ref('orders') }};

-- Identifier hole: syntax can appear valid while the resulting identifier is wrong.
select prefix_{{ suffix }} from customers;

-- A branch can generate mutually exclusive SQL fragments.
select customer_id from customers
{% if active_only %}where active = true{% else %}where archived = false{% endif %};

-- Incomplete edit: later tags must still be recognized.
select {{ unfinished(
{% macro later(value) %}{{ value }}{% endmacro %}
