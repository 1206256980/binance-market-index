function TimeRangeSelector({ value, onChange }) {
    const options = [
        { label: '6小时', hours: 6 },
        { label: '12小时', hours: 12 },
        { label: '1天', hours: 24 },
        { label: '3天', hours: 72 },
        { label: '7天', hours: 168 },
        { label: '14天', hours: 336 },
        { label: '21天', hours: 504 },
        { label: '30天', hours: 720 },
        { label: '40天', hours: 960 },
        { label: '60天', hours: 1440 },
        { label: '80天', hours: 1920 },
        { label: '100天', hours: 2400 },
        { label: '120天', hours: 2880 },
        { label: '180天', hours: 4320 }
    ]

    return (
        <div className="time-range-selector">
            {options.map(option => (
                <button
                    key={option.hours}
                    className={value === option.hours ? 'active' : ''}
                    onClick={() => onChange(option.hours)}
                >
                    {option.label}
                </button>
            ))}
        </div>
    )
}

export default TimeRangeSelector
