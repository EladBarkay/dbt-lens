{{ config(materialized='incremental') }}

{% set status_column = 'status' %}

select
    customer_id,
    {{ analytics.normalize_status(status_column) }} as normalized_status
from {{ ref('stg_customers') }}

{% if is_incremental() %}
where updated_at > (
    select max(updated_at) from {{ this }}
)
{% endif %}
